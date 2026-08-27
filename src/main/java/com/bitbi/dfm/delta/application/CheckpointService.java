package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.SegmentSeqRange;
import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.domain.events.CheckpointRecordedEvent;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds materialized checkpoints from the changelog (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Reconstruction is <b>incremental</b>: it seeds from the latest all-INSERT checkpoint frame@M and
 * folds only the segments with {@code first_seq > M}, persists a new frame@now, then materializes a
 * Parquet snapshot per table, records one {@link Checkpoint} row per table, and advances the site's
 * checkpoint pointer. The frame comes first because it is the only artifact that cannot be skipped:
 * a build that cannot write it must end, and ending it before the snapshots keeps that failure free
 * of durable cost (issue #153). Because the frame is a self-contained seed, segments at or below the checkpoint
 * can be pruned (T3.5b) without breaking the next build. A later build with no new segments still
 * rematerializes any table whose snapshot is missing (issue #128); a forced rebuild rematerializes
 * every table from the frame without moving the pointer.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final ChangelogSegmentService changelogSegmentService;
    private final CheckpointRepository checkpointRepository;
    private final DeltaSyncStateService syncStateService;
    private final S3CheckpointStorage checkpointStorage;
    private final SiteSchemaService siteSchemaService;
    private final DeltaMetrics metrics;
    private final DeltaParquetProperties parquetProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final CheckpointEpochGuard epochGuard;
    private final CheckpointRetryProperties retryProperties;
    private final ApplicationShutdownSignal shutdownSignal;
    /** One fold at a time in this JVM, so {@link #maxFoldBytes} bounds the process (issue #178). */
    private final CheckpointFoldBudget foldBudget;
    /**
     * The bound on the scratch <em>directory</em> this build shares with the completed-batch
     * workers (issue #150) — the per-file ceilings below cannot bound a count of files.
     */
    private final ParquetScratchBudget scratchBudget;
    private final Path tempDirectory;
    /** Per-table snapshot ceiling: crossing it skips that table (issue #138). */
    private final long maxTempBytes;
    /** Reload-frame ceiling: crossing it aborts the build, so it is deliberately its own key. */
    private final long maxFrameTempBytes;
    /**
     * Heap ceiling on the fold itself (issue #152) — the one bound that is not about disk, and the
     * one a growing site reaches first.
     */
    private final long maxFoldBytes;
    /**
     * Whether a bootstrap build whose whole history is a {@code FULL_SNAPSHOT} session may skip the
     * fold and stream (issue #292). The off switch, not the safety: the path is chosen automatically
     * and falls back on its own when the wire contract turns out not to hold.
     */
    private final boolean streamingBootstrap;
    /**
     * How many table snapshots the streaming path keeps open at once, and therefore how many passes
     * it makes over the local frame (issue #292).
     *
     * <p>The trade is heap against local reads and it has to be bounded on both sides. One writer
     * per table would put {@code delta.parquet.row-group-bytes} of buffer on the heap per table —
     * for a site with 86 tables at the shipped 16 MiB that is ~1.3 GiB, the ceiling this path exists
     * to remove, arriving from the other direction. One writer at a time would cost a pass over the
     * whole local frame per table. W of them costs {@code W x row-group-bytes} of heap and
     * {@code 1 + ceil(tables / W)} passes — a number that does not grow with the site's row count,
     * which is the property being bought.</p>
     */
    private final int snapshotWriters;

    /**
     * Whether an incremental build seeded from a frame joins that frame against the period's delta
     * instead of folding the site (issue #293). The rollback, not the safety — off, a large site
     * goes back to being bounded by {@code delta.checkpoint.max-fold-bytes} in full.
     */
    private final boolean streamingMerge;

    /**
     * How far the merge may partition a delta that does not fit the fold budget before it gives up
     * and aborts the build as it did before (issue #293). {@code 1} disables the fallback.
     */
    private final int maxMergePartitions;

    public CheckpointService(ChangelogSegmentRepository segmentRepository,
                             ChangelogSegmentService changelogSegmentService,
                             CheckpointRepository checkpointRepository,
                             DeltaSyncStateService syncStateService,
                             S3CheckpointStorage checkpointStorage,
                             SiteSchemaService siteSchemaService,
                             DeltaMetrics metrics,
                             DeltaParquetProperties parquetProperties,
                             ApplicationEventPublisher eventPublisher,
                             CheckpointEpochGuard epochGuard,
                             CheckpointRetryProperties retryProperties,
                             ApplicationShutdownSignal shutdownSignal,
                             CheckpointFoldBudget foldBudget,
                             ParquetScratchBudget scratchBudget,
                             // fully qualified: the delta wire Value is imported above
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.temp-dir:${java.io.tmpdir}}") String tempDirectory,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-temp-bytes:10737418240}") long maxTempBytes,
                             // Falls back to the per-table property, not to a literal: before #138
                             // one key governed both files, so an operator who had lowered it to
                             // fit a small scratch disk must keep the frame bounded by that same
                             // number until they say otherwise. application.yml always defines
                             // both keys, so this chain is the no-yml (test, embedded) path; the
                             // deployed one is the identical fallback written there.
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-frame-temp-bytes:"
                                             + "${delta.checkpoint.max-temp-bytes:10737418240}}")
                             long maxFrameTempBytes,
                             // Not a scratch ceiling: this one bounds heap, and 0 means "work it
                             // out from the heap I was given" (see resolveMaxFoldBytes).
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-fold-bytes:0}") long maxFoldBytes,
                             // The off switch for the bootstrap fast path (issue #292): a
                             // deployment that suspects it can go back to the general fold without
                             // shipping code.
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.streaming-bootstrap:true}")
                             boolean streamingBootstrap,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.snapshot-writers:8}") int snapshotWriters,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.streaming-merge:true}") boolean streamingMerge,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-merge-partitions:64}") int maxMergePartitions) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.checkpointRepository = checkpointRepository;
        this.syncStateService = syncStateService;
        this.checkpointStorage = checkpointStorage;
        this.siteSchemaService = siteSchemaService;
        this.metrics = metrics;
        this.parquetProperties = parquetProperties;
        this.eventPublisher = eventPublisher;
        this.epochGuard = epochGuard;
        this.retryProperties = retryProperties;
        this.shutdownSignal = shutdownSignal;
        this.foldBudget = foldBudget;
        this.scratchBudget = scratchBudget;
        this.tempDirectory = Path.of(tempDirectory);
        this.maxTempBytes = maxTempBytes;
        this.maxFrameTempBytes = maxFrameTempBytes;
        this.maxFoldBytes = resolveMaxFoldBytes(maxFoldBytes);
        this.streamingBootstrap = streamingBootstrap;
        // Named with its value, the #185/#251 rule: a zero here would open no writer at all and the
        // build would publish an empty checkpoint for every table, which is the kind of silent
        // wrong answer a startup refusal exists to replace.
        if (snapshotWriters < 1) {
            throw new IllegalArgumentException(
                    "delta.checkpoint.snapshot-writers must be at least 1, but was " + snapshotWriters);
        }
        this.snapshotWriters = snapshotWriters;
        this.streamingMerge = streamingMerge;
        if (maxMergePartitions < 1) {
            throw new IllegalArgumentException(
                    "delta.checkpoint.max-merge-partitions must be at least 1, but was "
                            + maxMergePartitions);
        }
        this.maxMergePartitions = maxMergePartitions;
    }

    /**
     * Resolve the fold's heap budget: anything non-positive (the shipped default) means auto — half
     * the max heap.
     *
     * <p>Derived rather than declared beside the deployment, which is where the scratch ceilings had
     * to go (#138): the process cannot see how large the directory it was handed is, but it can
     * always see its own {@code -Xmx}.</p>
     *
     * <p><b>Half rather than a quarter</b>, and the difference is what this ceiling is <em>for</em>
     * (raised in review). A quarter is the capacity-planning number — it leaves room for the second
     * concurrent build path, the same {@code 2 x} behind the scratch budget (#131), plus the ingest
     * the pod serves while it folds. But this guard is not a capacity plan; it is the last line
     * before an {@code OOMKill}, and a build refused here is refused <b>permanently</b>, taking
     * retention with it. Set at the capacity-planning value it would refuse folds that genuinely
     * fit: before this ticket the seed path held two to three full-site copies at once, so a site
     * building successfully today can have a fold near half the heap — and would have been refused
     * on the first nightly tick after the deployment that made its build cheaper. Half keeps that
     * regression out while still firing well before the heap does, since the fold is now the only
     * full-site copy. An operator who wants the concurrency headroom sets
     * {@code delta.checkpoint.max-fold-bytes} explicitly.</p>
     *
     * @param configured the configured value, or {@code 0} for auto
     * @return the budget in estimated retained bytes
     */
    static long resolveMaxFoldBytes(long configured) {
        return configured > 0 ? configured : Runtime.getRuntime().maxMemory() / 2;
    }

    /**
     * How this pass writes Parquet. Incremental work always advances {@code seq}. An idle
     * pass (no new segments) never does: it either retries missing keys or rewrites every table.
     */
    private enum SnapshotPass {
        INCREMENTAL,
        RETRY_MISSING,
        FORCE
    }

    /**
     * Build (or refresh) the checkpoint for a site by folding the latest checkpoint frame plus the
     * segments recorded since the checkpoint pointer. An idle site (no new segments) retries only
     * tables whose snapshot is missing.
     *
     * <p>An idle site with nothing to rematerialize answers <b>without folding</b> and returns an
     * empty map (issue #149) — the probe that decides reads only the {@code checkpoints} table, so
     * the frame is not downloaded. The returned fold is therefore "what this build produced", never
     * "what the site currently looks like"; a caller that wants the latter must ask for a rebuild.
     * No production caller reads the value at all.</p>
     *
     * <p>No {@code @Transactional}: the build spans frame + per-segment S3 downloads and per-table
     * S3 uploads — holding a HikariCP connection across those network calls would pin it for the
     * whole build (the pattern removed from the read path in 025-T3). Repository calls run in their
     * own short transactions; a failure mid-loop leaves idempotent per-table rows that the next
     * build overwrites, and the pointer only advances at the very end ({@code recordCheckpoint}).</p>
     *
     * <p>Each of those short transactions goes through {@link CheckpointEpochGuard}, which takes the
     * {@code site_sync_state} row lock a history wipe and a re-baseline both hold, and refuses the
     * write if the site's baseline epoch moved. A build overtaken by either is discarded — it
     * returns an empty fold rather than restoring the rows and checkpoint pointer of a baseline that
     * no longer exists (issues #136, #142).</p>
     *
     * @param siteId site identifier
     * @return folded state: table → row-identity → folded row (empty if there is nothing to fold)
     */
    public Map<String, Map<String, FoldedRow>> buildCheckpoint(UUID siteId) {
        return buildCheckpoint(siteId, true);
    }

    /**
     * As {@link #buildCheckpoint(UUID)}, with the wait for the process's fold budget optional
     * (issue #178).
     *
     * <p>For a caller with many sites to visit. {@code CheckpointScheduler} pays one full wait per
     * tick and asks without waiting afterwards, so a build that never finishes costs one wait
     * rather than one per remaining site — at 200 sites the difference is a tick of
     * {@code 200 x delta.checkpoint.fold-wait-seconds}, over which its own lock skips the following
     * nights and retention freezes for every site instead of the contended one.</p>
     *
     * @param siteId               site identifier
     * @param mayWaitForFoldBudget {@code false} to take the fold budget only if it is free now
     * @return folded state: table → row-identity → folded row (empty if there is nothing to fold)
     */
    public Map<String, Map<String, FoldedRow>> buildCheckpoint(UUID siteId, boolean mayWaitForFoldBudget) {
        return run(siteId, SnapshotPass.RETRY_MISSING, mayWaitForFoldBudget);
    }

    /**
     * Same fold as {@link #buildCheckpoint(UUID)}, but an idle site rewrites every table from the
     * frame. New segments still take the incremental path and advance the pointer.
     *
     * @param siteId site identifier
     * @return folded state: table → row-identity → folded row (empty if there is nothing to fold)
     */
    public Map<String, Map<String, FoldedRow>> rebuildFromFrame(UUID siteId) {
        return run(siteId, SnapshotPass.FORCE, true);
    }

    /**
     * Take the process's fold budget, then do everything else (issue #178).
     *
     * <p>The order is the point, and it was the other way round in the first cut of this ticket:
     * the site's sync state, its segment list and the frame's presence are all read <b>after</b> the
     * budget is held. Read before it, they would be as stale as the wait is long — up to
     * {@code delta.checkpoint.fold-wait-seconds} plus the whole of the neighbouring build — and a
     * forced rebuild parked on the semaphore while the nightly sweep built the same site would then
     * fold a segment list that {@code ChangelogRetentionService.prune} had already deleted from S3
     * behind the advanced pointer. That fails the build on a missing key, which is a fact about
     * nothing. Before this ticket the same gap existed but was a few S3 round trips wide.</p>
     *
     * <p>The cost is that a site with nothing to do holds the budget for one query and one S3
     * presence check rather than for nothing at all. That is the same trade the idle probe makes
     * (see {@link #build}), and it buys a build that folds what the site looks like now.</p>
     */
    private Map<String, Map<String, FoldedRow>> run(UUID siteId, SnapshotPass idlePass,
                                                    boolean mayWaitForFoldBudget) {
        try {
            return foldBudget.runExclusively(siteId, mayWaitForFoldBudget,
                    () -> runWithBudgetHeld(siteId, idlePass));
        } catch (CheckpointFoldBudget.BuildDeferredException e) {
            // The concurrency half of the same ceiling (issue #178), and the opposite verdict to the
            // fold ceiling's. Nothing was folded, so nothing about this site was learned: another
            // build held the process's fold budget. Counted on delta.checkpoint.builds.deferred
            // rather than on delta.checkpoint.builds.aborted, whose values are the refusals that
            // never repair themselves — this one is repaired by the neighbouring build finishing.
            //
            // Unless the wait was cut short, which is not contention at all: the wait ends itself
            // when the context starts closing, and counting that would move an alerting series
            // during every rollout that catches a build waiting — the same reason
            // BuildEndedByShutdownException records nothing (issue #162). Still re-thrown, so
            // DeltaCheckpointRebuildService can keep its durable flag for the next process.
            // And only a *spent* wait is contention worth counting. Once the nightly sweep has spent
            // its wait it probes every remaining site without waiting, so counting those would add
            // hundreds of increments to one collision — and the remedy this meter's own text
            // prescribes, raising the wait, is wrong for every one of them (raised in review).
            if (e.waitWasSpent()) {
                log.warn("{}", e.getMessage());
                metrics.checkpointBuildDeferred();
            } else if (e.endedEarly()) {
                log.info("Ending the checkpoint build for site {} before it started: {}",
                        siteId, e.getMessage());
            } else {
                log.debug("{}", e.getMessage());
            }
            throw e;
        }
    }

    private Map<String, Map<String, FoldedRow>> runWithBudgetHeld(UUID siteId, SnapshotPass idlePass) {
        try {
            // A build can have spent minutes waiting, so the process may be going away by the time
            // it inherits the budget. Without this it would read, download and fold a whole site
            // during the termination grace period, for a verdict issue #162 says it must not record.
            stopIfShuttingDown(siteId);
            // The epoch is read *before* the segments, and the order is load-bearing. Read the other
            // way round, a re-baseline (or a wipe) committing between the two would hand the build
            // the pre-reset segment list together with the new epoch: every guarded write would then
            // compare equal and be approved, and the build would fold the discarded baseline, upload
            // a frame at its last seq and move the pointer there — the resurrection the guard exists
            // to stop, arrived at through the guard. This way the epoch can only be older-or-equal
            // to the data it guards, which is the direction the guard refuses.
            DeltaSyncStateService.SyncStateView syncState = syncStateService.getSyncState(siteId);
            // Seq coverage only, two longs per segment (issue #212 review): held-back pending
            // segments make the committed set unbounded, and everything decided here — lossless
            // refold or not, anything new to fold or not — is a question about coverage. The fold
            // itself loads full entities, and only above its seed.
            List<SegmentSeqRange> ranges = segmentRepository.findSeqRangesBySiteIdOrderByFirstSeq(siteId);
            long checkpointSeq = syncState.lastCheckpointSeq();
            // The epoch this build belongs to. Every row it writes is checked against it under the
            // site_sync_state row lock, so a history wipe (issue #136) or a re-baseline (issue #142)
            // that commits mid-build discards the build instead of having its deletes undone by it.
            SiteEpoch epoch = syncState.epoch();
            ObjectPresence framePresence = checkpointSeq > 0
                    ? checkpointStorage.framePresence(siteId, checkpointSeq)
                    : ObjectPresence.ABSENT;
        // S3 refused to say whether the seed frame is there (issue #157). Every conclusion below
        // rests on absence being a fact, and this is not one: a blanket read denial on keys that do
        // exist answers exactly like a key that is gone. Acting on it would raise this subsystem's
        // loudest alarm — or, with no segments behind the site, spend one of the finite
        // rematerialize attempts #149 gave those rows, after which the site names itself on no work
        // list and does not return when the permission does. End the build instead: nothing is
        // recorded, nothing is spent, and the next tick answers the same question once the read is
        // allowed again. Deliberately not on delta.checkpoint.builds.aborted, whose contract is
        // aborts that never repair themselves; delta.s3.read-denied is the meter for this one, and
        // it is incremented where the denial is seen.
        //
        // Thrown rather than returned as an empty fold, for the same reason #162 made the shutdown
        // case distinguishable: DeltaCheckpointRebuildService cannot tell an empty fold from a
        // finished build, so it would log "rebuild completed" and spend the durable
        // rebuild_requested flag on a build that never ran.
            if (framePresence == ObjectPresence.UNKNOWN) {
                throw new FramePresenceUnknownException(siteId, checkpointSeq);
            }
            boolean haveFrame = framePresence == ObjectPresence.PRESENT;
            // "History pruned" means "a refold from the changelog alone would lose rows", and a
            // head at seq 1 stopped proving the opposite with issue #212: the prune used to delete
            // oldest-first unconditionally, leaving a contiguous suffix, but the hold-back can now
            // retain an older pending segment while younger processed neighbours are pruned — so a
            // gap can sit *behind* a retained head (a reinit re-pends interleaved segments out of
            // queue order, which is the concrete route). Contiguity from seq 1 is therefore
            // checked, not inferred from the head alone.
            boolean historyPruned = notSeedableFromScratch(ranges);

            // A frame@checkpointSeq must exist once the pointer advanced (uploadFrame precedes
            // recordCheckpoint). If it is genuinely gone — deleted; not merely unreadable, which
            // the tri-state above has already taken out of this path — a refold is lossless only
            // while the full history survives; after retention pruning it would silently publish a
            // truncated checkpoint and advance the pointer, making the loss durable. Refuse and let
            // the build fail loudly instead.
            if (checkpointSeq > 0 && !haveFrame && historyPruned) {
                return refuseRefold(siteId, ranges, checkpointSeq, epoch, idlePass);
            }
            if (ranges.isEmpty() && !haveFrame) {
                // Nothing has ever been checkpointed for this site and there is no changelog to
                // start from. For the nightly tick that is a routine visit to a site named by an
                // unmaterialized row, so it returns quietly — but a *forced* rebuild is somebody
                // asking a question, and answering "rebuilt" for a build with no source at all is
                // the false success issue #186 exists to remove.
                if (idlePass == SnapshotPass.FORCE) {
                    throw new NothingToRebuildException(siteId);
                }
                return Map.of();
            }
            return build(siteId, idlePass, ranges, checkpointSeq, epoch, haveFrame);
        } catch (FoldTooLargeException e) {
            // The heap twin of the frame ceiling (issue #152), and it belongs on the same meter for
            // the same reason (#153): the fold is deterministic for the same history and a site only
            // grows, so every following tick ends here too, with the pointer — and retention with
            // it — frozen. Nothing durable was written: the abort happens before the frame upload,
            // which is the build's first side effect since #153.
            //
            // Logged before it is counted, as the frame ceiling is: the counter validates its
            // reason and would otherwise replace this exception and swallow the only line naming
            // the site.
            log.error("The checkpoint fold for site {} outgrew delta.checkpoint.max-fold-bytes: an "
                    + "estimated {} bytes of heap against a budget of {}. Nothing was written — the "
                    + "pointer, the per-table keys and the frame stay where they were, and retention "
                    + "is frozen with the pointer. The next tick will fail identically, because the "
                    + "site's history has not shrunk. Raise the key together with the pod's heap "
                    + "(unset, the budget is half the max heap), or give the site a "
                    + "re-baseline so its fold starts from what the source still holds",
                    siteId, e.estimatedBytes(), e.budgetBytes());
            metrics.checkpointBuildAborted("fold_too_large");
            throw e;
        } catch (BuildEndedByShutdownException e) {
            // Not a failure of this build either, and above all not a fact about any table it was
            // writing (issue #162): the process is going away, so nothing it could still learn is
            // worth recording. Rows keep their last-good keys, the pointer stays, and the next
            // tick of the next process redoes the work from the same seed. Deliberately not
            // counted as delta.checkpoint.builds.aborted — that meter's contract is the aborts that
            // never repair themselves, and this one is repaired by the process that replaces us.
            log.info("Ending the checkpoint build for site {}: the application is shutting down, "
                    + "so no table verdict was recorded", siteId);
            return Map.of();
        } catch (CheckpointEpochGuard.EpochChangedException e) {
            // Not a failure of this build: the site's baseline was replaced under it, so there is
            // nothing left to publish. Whatever rows it did commit were taken by the wipe's (or the
            // re-baseline's) own deletes — they can only have committed before the row lock was
            // taken — and the objects it uploaded are orphans the next wipe sweeps.
            log.warn("Discarding the checkpoint build for site {}: {}", siteId, e.getMessage());
            throw new BuildDiscardedException(siteId, e.getMessage());
        }
    }

    /**
     * The seed frame is <b>gone</b> and the changelog cannot replace it. Two different facts hide
     * behind that, and they deserve two different answers (issue #149, review of #148).
     *
     * <p>"Gone" and not "unreadable": since issue #157 a frame S3 refused to talk about answers
     * {@code UNKNOWN} and never reaches this method, so both branches below are about an object
     * that S3 itself said is not there.</p>
     *
     * <p>With segments still arriving <b>above</b> the pointer, a refold would produce a
     * <em>truncated</em> checkpoint and make the loss durable by advancing the pointer over it:
     * that is the pre-existing "refusing lossy refold", it is about live data and it must keep
     * shouting, because the site is visited for those segments every night whatever this method
     * does — no attempt is spent, and no counter could ever quiet it.</p>
     *
     * <p>With <b>no</b> segments at all there is no history to refold, lossily or otherwise — the
     * frame was the site's entire checkpoint history and it is gone. Nothing the changelog can
     * offer will bring it back, so the message says so instead of blaming pruning, and every
     * still-retryable row of the site spends an attempt. That is what ends the nightly alarm: such
     * a site is on the tick's work list <em>only</em> because of those rows, so once they have
     * given up it is not visited at all, and {@code delta.checkpoint.tables.given-up} carries the
     * fact from then on. Recovery is a re-baseline or a history wipe, both of which delete the rows
     * outright.</p>
     *
     * <p><b>The state issue #212 created sits in between</b>: segments on record, every one of
     * them at or below the pointer, and at least one of them <b>held back pending queue work</b> —
     * the row retention can never remove, so without a bound this site would raise
     * {@code lossy_refold} nightly, spend nothing and never drain: #149's regression, reached
     * through #212's fix. Everything such segments hold is already inside the lost frame's fold,
     * so the changelog brings no new work — only the lossy refold being refused — and the visit is
     * charged to the retryable rows exactly as {@code history_gone} charges it. Once they have
     * given up, the visit ends <em>quietly</em> (an empty fold, no counter): the site stays pinned
     * to the work list by its held-back segments, {@code delta.checkpoint.tables.given-up} is the
     * standing signal, and a forced rebuild re-arms the rows as it always has. The
     * {@code lossy_refold} tag is kept while attempts last — the segments are real data and the
     * condition is the pruned-history one, not a vanished history.</p>
     *
     * <p><b>The drain is scoped to that state and no wider</b> (review round 2): a frame-gone site
     * whose below-pointer segments are all <em>processed</em> — the ordinary audit window of a
     * quiet site, which with the default window of 20 retention never emptied even before #212 —
     * keeps the never-quiets contract: loud nightly, no attempt spent, because that alarm is a
     * real, rebuild-recoverable data-loss condition that #212 did not create and must not
     * silence.</p>
     */
    private Map<String, Map<String, FoldedRow>> refuseRefold(UUID siteId,
                                                             List<SegmentSeqRange> ranges,
                                                             long checkpointSeq,
                                                             SiteEpoch epoch,
                                                             SnapshotPass pass) {
        // Unless a wipe took them. It deletes the frame and the segments after committing the
        // new epoch, so a build that read the pointer just before it sees exactly this state —
        // and raising the loudest alarm in this subsystem for a routine operator action would
        // be wrong. Re-read the epoch and discard instead. (The re-read can itself lose the
        // race, in which case the alarm is raised as before; a wipe repeated on that site
        // clears it, since the pointer is 0 by then.) A re-baseline moves the same epoch and is
        // just as routine, so it is covered by the same re-read.
        if (!syncStateService.getSyncState(siteId).epoch().equals(epoch)) {
            log.warn("Discarding the checkpoint build for site {}: its history was replaced "
                    + "while the build was reading, taking frame@{} with it", siteId, checkpointSeq);
            throw new BuildDiscardedException(siteId,
                    "its history was replaced while the build was reading, taking frame@"
                            + checkpointSeq + " with it");
        }
        if (ranges.isEmpty()) {
            metrics.checkpointBuildAborted("history_gone");
            settleSiteWide(siteId, epoch, pass);
            log.error("Checkpoint frame@{} for site {} is gone and the changelog is empty — there "
                    + "is no history left to rebuild this site's checkpoints from. Recovery is a "
                    + "re-baseline or a history wipe; a forced rebuild re-arms the retry but cannot "
                    + "conjure a frame. The nightly retry gives up after {} such nights, after "
                    + "which the site is only reachable through a forced rebuild. This is a real "
                    + "absence: since issue #157 a read denial answers UNKNOWN and skips the site "
                    + "without spending an attempt",
                    checkpointSeq, siteId, retryProperties.maxMaterializeAttempts());
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Checkpoint frame@" + checkpointSeq + " for site " + siteId
                            + " is gone and the changelog is empty — there is no history left "
                            + "to rebuild this site's checkpoints from",
                    null);
        }
        boolean nothingAboveCheckpoint = ranges.stream()
                .allMatch(range -> range.getLastSeq() <= checkpointSeq);
        // R2-7 of the #212 review scoped this drain to the state #212 actually created: it applies
        // only while a *held-back pending* segment sits below the pointer — the row retention can
        // never remove. A frame-gone site whose below-pointer segments are all processed is the
        // pre-#212 population (with the default window of 20, retention never emptied a quiet
        // site's list), and it keeps the never-quiets contract below: draining it would have
        // silenced a real, rebuild-recoverable data-loss alarm after five nights.
        if (nothingAboveCheckpoint
                && segmentRepository.existsCommittedPendingBelowCheckpoint(siteId, checkpointSeq)) {
            // Everything the changelog still holds is already inside the lost frame's fold, so no
            // new work will ever change this verdict — and a held-back pending segment can keep
            // the list non-empty for ever. Bound the dedicated retry the way history_gone does
            // (issue #149): spend an attempt per retryable row on a scheduled pass, re-arm on a
            // forced one, and once every row has given up, end the visit quietly — the site stays
            // pinned to the work list by its held-back segments, and
            // delta.checkpoint.tables.given-up is the standing signal from then on.
            if (pass != SnapshotPass.FORCE && !hasRetryableUnmaterializedTables(siteId)) {
                log.debug("Frame@{} for site {} is gone and only below-checkpoint segments remain; "
                        + "the retry has already been spent — ending the visit quietly", checkpointSeq, siteId);
                return Map.of();
            }
            metrics.checkpointBuildAborted("lossy_refold");
            settleSiteWide(siteId, epoch, pass);
            // Pass-aware (R2-6): #186 puts this text verbatim into the admin lastRebuildMessage,
            // and on the forced pass settleSiteWide re-arms instead of spending — telling the
            // operator their documented recovery action burned an attempt would be false.
            String settled = pass == SnapshotPass.FORCE
                    ? "The forced rebuild re-armed the per-table retry (the operator asserting the "
                            + "cause was dealt with) but cannot conjure a frame"
                    : "Every remaining segment is below the checkpoint and held back pending queue "
                            + "work, so an attempt was spent (issue #149's drain): after "
                            + retryProperties.maxMaterializeAttempts()
                            + " such nights the retry stops and "
                            + "delta.checkpoint.tables.given-up carries the fact";
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Checkpoint frame@" + checkpointSeq + " for site " + siteId
                            + " is gone and earlier segments are pruned — refusing lossy refold. "
                            + settled + "; recovery is a re-baseline or a history wipe",
                    null);
        }
        // Counted with the frame ceiling (issue #153) and for the same reason: the pointer
        // stays where it is, retention stops with it, and nothing about waiting repairs
        // either. Counting only one of the two permanent freezes would make the alert the
        // operator guide asks for silently miss half of them.
        metrics.checkpointBuildAborted("lossy_refold");
        throw new S3CheckpointStorage.CheckpointStorageException(
                "Checkpoint frame@" + checkpointSeq + " for site " + siteId
                        + " is gone and earlier segments are pruned — refusing lossy refold",
                null);
    }

    /**
     * Whether this coverage cannot seed a lossless refold from seq 1 (issue #212): empty, a head
     * above 1, or a gap anywhere behind it.
     */
    private static boolean notSeedableFromScratch(List<SegmentSeqRange> ranges) {
        return ranges.isEmpty() || ranges.get(0).getFirstSeq() > 1 || hasSeqGap(ranges);
    }

    /** The coverage view of one loaded entity — for the post-load re-verification (R2-5). */
    private static SegmentSeqRange rangeOf(ChangelogSegment segment) {
        return new SegmentSeqRange() {
            @Override
            public long getFirstSeq() {
                return segment.getFirstSeq();
            }

            @Override
            public long getLastSeq() {
                return segment.getLastSeq();
            }
        };
    }

    /**
     * Whether the ordered seq coverage leaves any sequence strictly uncovered (issue #212).
     *
     * <p>A gap is {@code next.firstSeq > maxLastSeqSoFar + 1} — sequences that no segment carries.
     * Overlapping or contained ranges are tolerated: they repeat data, they lose none, and refusing
     * a healthy site's refold over them would turn this guard into the defect it prevents.</p>
     */
    private static boolean hasSeqGap(List<SegmentSeqRange> ranges) {
        long covered = Long.MIN_VALUE;
        for (SegmentSeqRange range : ranges) {
            if (covered != Long.MIN_VALUE && range.getFirstSeq() > covered + 1) {
                return true;
            }
            covered = Math.max(covered, range.getLastSeq());
        }
        return false;
    }

    /**
     * Charge a site-wide abort to the rows that keep the site on the nightly work list.
     *
     * <p>The abort happens before any table is reached, so no per-table catch can record it — yet
     * it is exactly as final for those rows as an unrenderable value would be, and without this
     * they would be retried nightly forever for a build that cannot start.</p>
     */
    private void spendAnAttemptOnEveryRetryableTable(UUID siteId, SiteEpoch epoch) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (checkpoint.hasGivenUpMaterializing(retryProperties.maxMaterializeAttempts())
                    || checkpoint.getS3KeyParquet() != null) {
                continue;
            }
            checkpoint.recordFailedMaterialization();
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        }
    }

    /**
     * The forced-rebuild counterpart of {@link #spendAnAttemptOnEveryRetryableTable}: put every
     * unmaterialized row of the site back into the nightly population.
     *
     * <p>A forced rebuild that ends in a site-wide abort still means what a forced rebuild always
     * means — the operator asserting the cause has been dealt with. Charging it an attempt would
     * make the documented recovery action the fastest way to exhaust the retry.</p>
     */
    private void rearmEveryUnmaterializedTable(UUID siteId, SiteEpoch epoch) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (checkpoint.getS3KeyParquet() != null || checkpoint.materializeAttempts() == 0) {
                continue;
            }
            checkpoint.rearmMaterialization();
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        }
    }

    /**
     * The fold and everything it feeds, with the process's fold budget already held by
     * {@link #run} (issue #178).
     *
     * <p>The budget is taken <b>outside</b> {@code phase=total}, so no wait reaches
     * {@code delta.checkpoint.duration} — neither a deferred build, which did no work and would
     * otherwise contribute the one sample an operator reads that timer's maximum from, nor a build
     * that waited and then ran. That second case is why the wait has a meter of its own:
     * {@code delta.checkpoint.fold.wait} is the only place contention short of a deferral is
     * visible, since {@code delta.checkpoint.builds.deferred} stays at zero for a build that
     * eventually got the budget.</p>
     *
     * <p>The budget covers the whole build rather than the fold loop, because the folded state is
     * what {@code writeSnapshots} iterates: the heap is held until the last table has been
     * uploaded. The cost is that an <em>idle</em> visit — the query below answering "nothing to
     * rematerialize" — holds the budget for the length of that query, and can in principle be
     * deferred. Answering it before taking the budget would mean reading the site's state outside
     * the exclusion, which is the staleness this ticket's second review round removed.</p>
     */
    private Map<String, Map<String, FoldedRow>> build(UUID siteId,
                                                      SnapshotPass idlePass,
                                                      List<SegmentSeqRange> ranges,
                                                      long checkpointSeq,
                                                      SiteEpoch epoch,
                                                      boolean haveFrame) {
        // Empty incremental work still belongs in phase=total: the probe below runs inside it.
        return metrics.timeCheckpoint(() -> {
            long foldFrom = haveFrame ? checkpointSeq : 0L;
            boolean nothingNew = ranges.stream().noneMatch(range -> range.getFirstSeq() > foldFrom);

            // The idle probe comes before the frame download and the fold, not after them (issue
            // #149). Since #137 a site with one unmaterialized row is named by every tick, so
            // "there is nothing to do here" is the *normal* answer on this path — and it is
            // answered by one query against `checkpoints`. Downloading a whole-site frame and
            // folding it in heap only to discard it was the price of asking the question in the
            // wrong order. Since #212 the answer is read off the seq ranges, so an idle visit —
            // now the nightly steady state of a site pinned to the work list by held-back
            // segments — hydrates no entity at all.
            if (nothingNew
                    && (!haveFrame || (idlePass == SnapshotPass.RETRY_MISSING
                            && !hasRetryableUnmaterializedTables(siteId)))) {
                return Map.of();
            }

            // Entities only above the fold's seed (issue #212 review): with a frame, everything at
            // or below the pointer is already inside it; without one, afterSeq 0 loads the whole
            // committed set for the full refold, exactly as before.
            List<ChangelogSegment> newSegments =
                    segmentRepository.findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq(siteId, foldFrom);

            // R2-5 of the #212 review: the coverage read above and this entity load are two
            // transactions with an S3 round trip (the frame probe) between them, and a deleter
            // that bumps no epoch — batch retention's 45-day horizon, a sibling replica's prune —
            // can remove rows in the window. On the full-refold path that would fold a silently
            // gapped history into truncated checkpoints and advance the pointer over the loss, so
            // contiguity is re-verified on the list actually folded. Thrown without counting:
            // unlike the refusals in refuseRefold this is a transient race, and the next tick
            // re-reads and classifies the state properly (the read-denial rule — one tick's cost,
            // off the permanent meter).
            if (!haveFrame && checkpointSeq > 0
                    && notSeedableFromScratch(newSegments.stream()
                            .map(CheckpointService::rangeOf).toList())) {
                throw new S3CheckpointStorage.CheckpointStorageException(
                        "The changelog of site " + siteId + " changed between the coverage read "
                                + "and the fold — refusing the refold; the next tick re-reads and "
                                + "classifies the state",
                        null);
            }

            // The degenerate case the fold does not need to be paid for (issue #292): a site with
            // no seed frame whose entire history is one FULL_SNAPSHOT session. The wire contract
            // says every record of such a session is an INSERT, so the fold is the identity map and
            // the frame is the input re-emitted. Taken before foldSite because the fold is the one
            // thing this path removes; it writes nothing durable until it knows the contract held,
            // so a violation simply falls through to the general path below.
            if (streamingBootstrap && !haveFrame && checkpointSeq == 0 && !newSegments.isEmpty()
                    && newSegments.stream().allMatch(CheckpointService::isFullSnapshot)) {
                Map<String, Map<String, FoldedRow>> streamed =
                        buildFromSnapshotStream(siteId, newSegments, epoch);
                if (streamed != null) {
                    return streamed;
                }
            }

            // The nightly steady state (issue #293): there is a seed frame, so the site does not
            // have to be in heap to be re-emitted — the delta is folded and the frame is streamed
            // past it. What is left below is the build that has no frame to stream: a bootstrap
            // whose history is not one whole FULL_SNAPSHOT session, which has no base to join
            // against and folds its own records, exactly as it always did.
            if (streamingMerge && haveFrame) {
                return buildByMerge(siteId, idlePass, checkpointSeq, epoch, newSegments);
            }

            Map<String, Map<String, FoldedRow>> state =
                    foldSite(siteId, checkpointSeq, haveFrame, newSegments);

            if (newSegments.isEmpty()) {
                writeSnapshots(siteId, state, checkpointSeq, idlePass, epoch);
                return state;
            }
            return materialize(siteId, state, newSegments, epoch);
        });
    }

    /**
     * Fold the site: the seed frame first, then every new segment, one record at a time.
     *
     * <p>Nothing between S3 and the fold is retained (issue #152). The frame used to arrive as a
     * gzipped {@code byte[]} that {@code ChangelogCodec.parse} expanded into a {@code List} of every
     * record in the site, and the new segments were collected into a second such list before a
     * single {@code fold} call that <em>copied</em> the seed — four full-site copies at the peak, on
     * a pod whose memory limit is measured in gigabytes. Now one state is built in place and each
     * record is dropped as soon as it has been applied.</p>
     *
     * <p>The two meters keep their meaning: {@code phase=download_frame} is time spent reading the
     * frame off the network, measured through {@link TimingInputStream} because the transfer is now
     * interleaved with the fold rather than finished before it. {@code phase=fold} is everything
     * else — the segment downloads it always covered, and now also the seed frame's own fold, which
     * was untimed while it sat between the two phases.</p>
     *
     * <p>Streaming makes the peak smaller; it does not make it bounded. What is still proportional
     * to the site's row count is the fold itself, so it is folded <b>against a budget</b>
     * ({@link BudgetedFold}) and a site that outgrows the heap is refused rather than left to be
     * {@code OOMKilled} halfway through.</p>
     */
    private Map<String, Map<String, FoldedRow>> foldSite(UUID siteId,
                                                         long checkpointSeq,
                                                         boolean haveFrame,
                                                         List<ChangelogSegment> newSegments) {
        BudgetedFold fold = new BudgetedFold(siteId, maxFoldBytes);
        long startedAt = System.nanoTime();
        // Written by foldFrame even when it throws — an abort halfway through the frame would
        // otherwise report download_frame=0 and charge the whole transfer to fold, on exactly the
        // build whose phases are worth looking at.
        long[] frameReadNanos = {0L};
        try {
            if (haveFrame) {
                foldFrame(siteId, checkpointSeq, fold, frameReadNanos);
            }
            for (ChangelogSegment segment : newSegments) {
                foldSegment(siteId, segment, fold::apply);
            }
        } finally {
            // Recorded even when the fold ended in an abort: a build that ran out of budget is
            // exactly the one whose phases an operator wants to see.
            if (haveFrame) {
                metrics.recordCheckpointPhase("download_frame", frameReadNanos[0]);
            }
            metrics.recordCheckpointPhase("fold", System.nanoTime() - startedAt - frameReadNanos[0]);
        }
        reportFoldSize(siteId, fold);
        return fold.state();
    }

    /**
     * Say how close this site is to the ceiling <em>before</em> it reaches it.
     *
     * <p>Without this the first word an operator gets is the abort itself, and the fold is the one
     * term of the checkpoint budget that nothing else makes visible: the scratch ceilings show up as
     * files on a volume, while the fold exists only while the build runs.</p>
     *
     * <p>Against the <b>peak</b>, not the size the fold happened to end at — the ceiling is enforced
     * on the running total, so a site whose fold rises and then falls back (a night's segments
     * inserting before they bulk-delete) would otherwise stay quiet at DEBUG right up to the tick
     * whose peak crosses the budget, which is precisely the warning this exists to give.</p>
     */
    private void reportFoldSize(UUID siteId, BudgetedFold fold) {
        reportFoldSize(siteId, fold.peakEstimatedBytes());
    }

    /** As above, for the merge path, whose peak is the largest partition's delta (issue #293). */
    private void reportFoldSize(UUID siteId, long bytes) {
        // On the meter as well as in the log, for the reason #153 put the abort on one: the band
        // below the ceiling is the only warning that precedes a permanent abort, and an alert
        // cannot be written on a log line. A build that aborted does not reach here — its size is
        // the counter's business, and recording it would put the one over-budget sample into the
        // series an operator reads as "how much room is left".
        metrics.recordCheckpointFoldBytes(bytes);
        if (bytes * 100 >= maxFoldBytes * FOLD_BUDGET_WARN_PERCENT) {
            log.warn("The checkpoint fold for site {} holds an estimated {} bytes of heap, {}% of "
                    + "delta.checkpoint.max-fold-bytes ({}). The build is refused outright once it "
                    + "crosses that, so raise the key (and the pod's heap with it) before this site "
                    + "grows further", siteId, bytes, bytes * 100 / Math.max(1L, maxFoldBytes), maxFoldBytes);
        } else {
            log.debug("The checkpoint fold for site {} holds an estimated {} bytes of heap, against "
                    + "delta.checkpoint.max-fold-bytes ({})", siteId, bytes, maxFoldBytes);
        }
    }

    /** How full the fold budget may get before a build starts saying so. */
    private static final int FOLD_BUDGET_WARN_PERCENT = 75;

    /**
     * Stream the seed frame into the fold.
     *
     * <p>{@code readNanos} is filled in whichever way this ends — it is the caller's
     * {@code phase=download_frame} sample, and an abort mid-frame is when the split between
     * transfer and fold is most worth having. The {@code GetObject} itself is timed separately from
     * the body, and timed <em>even when it throws</em>: during a read outage every site of the tick
     * would otherwise contribute a zero-nanosecond sample, and the timer would read as though frame
     * downloads had got faster exactly while they were failing.</p>
     *
     * <p>Every failure of the body is renamed. The one that actually fires is
     * {@code UncheckedIOException}: {@code ChangelogCodec.forEach} wraps each read and parse failure
     * into one whose message mentions neither the site nor the key, while the checked
     * {@code IOException} can only come from the close. {@code RuntimeException} is caught with them
     * because the AWS SDK raises {@code SdkClientException} — not an {@code IOException} — for a
     * body that ends short of its content length. Before streaming, {@code download} named the
     * object in a {@code CheckpointStorageException}; that is what this restores. The one exception
     * that must keep its own type is the fold's own abort, which is re-thrown untouched.</p>
     */
    private void foldFrame(UUID siteId, long checkpointSeq, BudgetedFold fold, long[] readNanos) {
        long openedAt = System.nanoTime();
        InputStream opened;
        try {
            // Outside the body's try: openFrame already names the key it could not read, and a
            // failure here must not be re-wrapped as if the frame had been read and rejected.
            opened = checkpointStorage.openFrame(siteId, checkpointSeq);
        } finally {
            readNanos[0] = System.nanoTime() - openedAt;
        }
        try (InputStream frame = opened) {
            TimingInputStream timed = new TimingInputStream(frame);
            try {
                ChangelogCodec.forEach(timed, fold::apply);
            } finally {
                readNanos[0] += timed.readNanos();
            }
        } catch (FoldTooLargeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Failed to read the checkpoint frame of site " + siteId + " at seq " + checkpointSeq, e);
        }
    }

    /**
     * Stream one segment into the fold, naming it if it cannot be read.
     *
     * <p>The same renaming the frame gets, and for the same regression: {@code readRecords} used to
     * surface a mid-transfer failure as a {@code SegmentStorageException} carrying the segment key,
     * while the streaming path raises {@code UncheckedIOException("Failed to stream change
     * records")}. {@code CheckpointScheduler} logs only {@code e.getMessage()}, so without this the
     * failing segment is not in the logs at all.</p>
     */
    private void foldSegment(UUID siteId, ChangelogSegment segment,
                             java.util.function.Consumer<ChangeRecord> consumer) {
        try {
            changelogSegmentService.forEachRecord(segment.getS3Key(), consumer);
        } catch (FoldTooLargeException | BootstrapFrameWriter.NotAFullSnapshotException
                | ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            // The consumer's own refusals, not the segment's: renaming them would report a fold
            // that outgrew its budget, a frame that outgrew its ceiling or a full scratch directory
            // as an unreadable object, and send an operator to S3 for a local problem.
            throw e;
        } catch (RuntimeException e) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Failed to read changelog segment " + segment.getS3Key() + " of site " + siteId, e);
        }
    }

    /**
     * One site's fold, with a ceiling on how much heap it may hold (issue #152).
     *
     * <p>The running total is kept by {@link ChangelogFold#apply}, which returns what each record
     * did to the state's size, so the budget costs one addition per record rather than a walk over
     * the fold — the weighing itself is proportional to the width of the row that record touches,
     * no wider than the array copy the fold does for it anyway. It is an estimate — see
     * {@link ChangelogFold#estimatedRetainedBytes(String, ChangelogFold.FoldedRow)} — and it is
     * compared against a budget expressed in the same units, so the two are wrong together or not
     * at all.</p>
     *
     * <p><b>The ceiling is per build and the process holds one build at a time</b>, so it bounds the
     * process (issue #178). It did not before: two folds at 45% of the budget each crossed nothing
     * and still exhausted the heap between them, which takes a forced rebuild running beside the
     * nightly sweep — the {@code 2 x} the scratch budget still reserves for on disk. What closes it
     * is {@link CheckpointFoldBudget}, an exclusion held for the whole build rather than a running
     * total shared between folds; a build that cannot have it within
     * {@code delta.checkpoint.fold-wait-seconds} is <em>deferred</em>, never refused, so no
     * concurrency-caused outcome reaches the abort counter.</p>
     */
    private static final class BudgetedFold {

        private final Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        private final UUID siteId;
        private final long maxBytes;
        private long bytes;
        private long peakBytes;

        private BudgetedFold(UUID siteId, long maxBytes) {
            this.siteId = siteId;
            this.maxBytes = maxBytes;
        }

        /**
         * Fold one record, then stop the build if the fold no longer fits.
         *
         * <p>Checked after applying rather than before, because the cost of one record is only
         * known once it has been applied — and one record over the ceiling is not what runs a pod
         * out of memory.</p>
         */
        private void apply(ChangeRecord record) {
            bytes += ChangelogFold.apply(state, record);
            peakBytes = Math.max(peakBytes, bytes);
            if (bytes > maxBytes) {
                throw new FoldTooLargeException(siteId, bytes, maxBytes);
            }
        }

        private Map<String, Map<String, FoldedRow>> state() {
            return state;
        }

        /** The largest the fold ever was — what the ceiling is enforced against, record by record. */
        private long peakEstimatedBytes() {
            return peakBytes;
        }
    }

    /** {@code SessionMode.FULL_SNAPSHOT} as {@code ChangelogSegmentService} records it on a segment. */
    private static final String FULL_SNAPSHOT_MODE = "FULL_SNAPSHOT";

    private static boolean isFullSnapshot(ChangelogSegment segment) {
        return FULL_SNAPSHOT_MODE.equals(segment.getMode());
    }

    /**
     * Build an incremental checkpoint by joining the seed frame against the period's delta
     * (issue #293).
     *
     * <h2>Which side is in heap</h2>
     *
     * <p>{@link #foldSite} folds the frame and then the segments into one map of every surviving
     * row — the site — so {@code delta.checkpoint.max-fold-bytes} bounds the <em>site</em>, and a
     * site that outgrows it never shrinks back. Here the delta is folded and the frame is streamed
     * past it ({@link ChangelogMerge}), which puts the night's work in heap and the site on the
     * wire. The frame is written locally first and the snapshots are then written from that file,
     * exactly as the streaming bootstrap does — {@code 1 + ceil(tables / W)} passes over a local
     * file, none of which holds more than one record and {@code W} row-group buffers.</p>
     *
     * <h2>The idle visit is the same shape with an empty delta</h2>
     *
     * <p>A site with nothing new above the pointer is visited for its unmaterialized rows (issues
     * #128, #137, #149) and a forced rebuild is visited unconditionally. Both used to fold the whole
     * site purely to re-emit its snapshots, so the ceiling applied to a build with no changes at
     * all. With no segments the merge is the identity and the local frame is the frame S3 already
     * holds: nothing is uploaded, the pointer does not move, and no {@code CheckpointRecordedEvent}
     * is published — the fold has not changed, so retention stays monotonic.</p>
     *
     * @return an empty map. There is no fold to return, which is the point; production callers
     *         ignore the value, and since issue #292 an empty one no longer means "nothing was
     *         done"
     */
    private Map<String, Map<String, FoldedRow>> buildByMerge(UUID siteId,
                                                             SnapshotPass idlePass,
                                                             long checkpointSeq,
                                                             SiteEpoch epoch,
                                                             List<ChangelogSegment> newSegments) {
        boolean advancing = !newSegments.isEmpty();
        long seq = advancing ? newSegments.get(newSegments.size() - 1).getLastSeq() : checkpointSeq;
        SnapshotPass pass = advancing ? SnapshotPass.INCREMENTAL : idlePass;

        prepareScratchDirectory();
        Path frame = createScratchFile(siteId, ".pb.gz");
        // One lease per attempt, and the successful attempt's is held for the whole build: the
        // snapshots are written by re-reading this file, so its bytes are on the volume until the
        // last table is done. A partitioned retry rewrites the file from scratch, and its lease has
        // to go with the bytes it charged for — CappedOutputStream charges as it writes and a lease
        // gives everything back only on close.
        ScratchLease[] lease = {null};
        try {
            CheckpointFrameWriter.FrameManifest manifest =
                    mergeIntoFrame(siteId, checkpointSeq, seq, newSegments, frame, lease);

            // The same order the other two paths take, and for the same reasons (issue #153):
            // notice a closing process before the longest single call of the build, and check the
            // epoch with nothing uploaded so a wipe that has already committed is seen before the
            // object is in the bucket rather than after.
            stopIfShuttingDown(siteId);
            epochGuard.requireEpoch(siteId, epoch);
            if (advancing) {
                withFrameCeilingReported(siteId, seq, () ->
                        metrics.timeCheckpointPhase("upload", () ->
                                checkpointStorage.uploadFrame(siteId, seq, frame)));
            }

            int passes = writeSnapshotsFromFrame(siteId, frame, manifest, seq, epoch, pass);
            log.info("Merged the checkpoint of site {} at seq {}: {} record(s) across {} table(s) "
                    + "from {} segment(s), {} pass(es) over the local frame with {} snapshot "
                    + "writer(s), no site fold", siteId, seq, manifest.records(),
                    manifest.tables().size(), newSegments.size(), passes, snapshotWriters);

            if (advancing) {
                epochGuard.inEpoch(siteId, epoch, () -> syncStateService.recordCheckpoint(siteId, seq));
                publishCheckpointRecorded(siteId, seq, epoch);
            }
            return Map.of();
        } finally {
            deleteQuietly(frame, "_frame", siteId);
            if (lease[0] != null) {
                lease[0].close();
            }
        }
    }

    /**
     * Write the new frame: the period's delta folded into heap, the old frame streamed past it.
     *
     * <h2>The fallback, when the delta itself does not fit</h2>
     *
     * <p>A delta outgrows the budget when a site's builds have not run for a long time, or when the
     * client streams a very large incremental session — the DBF client opens {@code CONTINUOUS} for
     * an incremental change set of a million records or more, so this is a shape the wire contract
     * produces rather than a hypothetical. Rather than abort, the merge is re-run in {@code K} hash
     * partitions of the row identity, applied to both sides so a row and its changes always land in
     * the same pass; each pass then holds about {@code 1/K} of the delta. {@code K} is found by
     * catching the refusal and multiplying, because the delta's size is not known before it is
     * folded and a configured constant would be wrong in both directions.</p>
     *
     * <p><b>It is the fallback and not the design</b>, and the cost says why: every partition
     * re-reads the seed frame and every segment of the period, so the work is {@code K} times the
     * work and {@code K} grows with the delta. It is here so that a build that would have been
     * refused finishes; a rate on {@code delta.checkpoint.builds.partitioned} says the budget wants
     * raising. Two properties are given up with it, both deliberately: the rows of one table come
     * out partition by partition rather than in the fold's order, and the frame is written once per
     * attempt, so an attempt that refuses is thrown away whole. Nothing durable exists at that
     * point — the frame is a local scratch file that has not been uploaded — which is what makes
     * the retry safe, and is the same property the streaming bootstrap leans on.</p>
     *
     * <p>{@code delta.checkpoint.max-merge-partitions} bounds the escalation. Past it the build
     * ends on {@code FoldTooLargeException} exactly as it did before this ticket, so
     * {@code builds.aborted{reason=fold_too_large}} keeps meaning "this site cannot be built at
     * this budget".</p>
     */
    private CheckpointFrameWriter.FrameManifest mergeIntoFrame(UUID siteId,
                                                               long checkpointSeq,
                                                               long seq,
                                                               List<ChangelogSegment> newSegments,
                                                               Path frame,
                                                               ScratchLease[] lease) {
        long startedAt = System.nanoTime();
        // Written by every attempt: phase=download_frame is the transfer, and a partitioned build
        // pays for it once per partition, which is exactly what an operator needs to see.
        long[] frameReadNanos = {0L};
        int partitions = 1;
        try {
            while (true) {
                lease[0] = scratchBudget.open(ParquetScratchBudget.CHECKPOINT_FRAME);
                try {
                    return mergeAttempt(siteId, checkpointSeq, newSegments, frame, lease[0],
                            partitions, frameReadNanos);
                } catch (FoldTooLargeException e) {
                    lease[0].close();
                    lease[0] = null;
                    if (partitions >= maxMergePartitions) {
                        throw e;
                    }
                    if (partitions == 1) {
                        // Once per build, not once per partition: the series counts builds that
                        // needed the fallback, and an escalation is one build still.
                        metrics.checkpointBuildPartitioned();
                    }
                    partitions = (int) Math.min(
                            (long) partitions * PARTITION_GROWTH, maxMergePartitions);
                    log.warn("The delta of site {} did not fit delta.checkpoint.max-fold-bytes "
                            + "(an estimated {} bytes against {}), so its checkpoint is merged in "
                            + "{} hash partitions instead — the build finishes, at the cost of "
                            + "re-reading the seed frame and all {} segment(s) once per partition. "
                            + "Raise the key (and the pod's heap with it) if this is not a one-off",
                            siteId, e.estimatedBytes(), e.budgetBytes(), partitions,
                            newSegments.size());
                }
            }
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            throw reportFrameCeiling(siteId, seq, e);
        } finally {
            metrics.recordCheckpointPhase("download_frame", frameReadNanos[0]);
            metrics.recordCheckpointPhase("fold", System.nanoTime() - startedAt - frameReadNanos[0]);
        }
    }

    /** How fast the merge escalates once a delta has refused to fit: the ticket's {@code K x 4}. */
    private static final int PARTITION_GROWTH = 4;

    /** One attempt at the merged frame, in {@code partitions} passes over both sides. */
    private CheckpointFrameWriter.FrameManifest mergeAttempt(UUID siteId,
                                                              long checkpointSeq,
                                                              List<ChangelogSegment> newSegments,
                                                              Path frame,
                                                              ScratchLease lease,
                                                              int partitions,
                                                              long[] frameReadNanos) {
        try (OutputStream out = new CappedOutputStream(
                        Files.newOutputStream(frame), maxFrameTempBytes, lease);
                CheckpointFrameWriter writer = CheckpointFrameWriter.open(out)) {
            long peakBytes = 0L;
            for (int partition = 0; partition < partitions; partition++) {
                stopIfShuttingDown(siteId);
                BudgetedMerge merge = new BudgetedMerge(siteId, maxFoldBytes, partitions, partition);
                for (ChangelogSegment segment : newSegments) {
                    foldSegment(siteId, segment, merge::apply);
                }
                streamFrameThrough(siteId, checkpointSeq, merge, writer::accept, frameReadNanos);
                merge.drain(writer::accept);
                peakBytes = Math.max(peakBytes, merge.peakEstimatedBytes());
            }
            // The band below the ceiling, on the same meter the fold reports (issue #152). The
            // largest partition is the one that decides whether this build fits, so it is the
            // sample; on the unpartitioned path there is only one.
            reportFoldSize(siteId, peakBytes);
            return writer.manifest();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
        }
    }

    /**
     * Stream the seed frame through the merge and into the new frame.
     *
     * <p>{@code frameReadNanos} is filled in whichever way this ends, and is added to rather than
     * assigned: a partitioned build streams the frame once per partition and the samples belong
     * together. The {@code GetObject} itself is timed even when it throws, for the reason
     * {@link #foldFrame} states — during a read outage every site of the tick would otherwise
     * contribute a zero-nanosecond sample.</p>
     *
     * <p>The consumer <em>writes</em> here, unlike the fold's, so its own refusals — the frame's
     * two ceilings — travel out untouched. Renaming them would report a full scratch directory as
     * an unreadable object and send an operator to S3 for a local problem.</p>
     */
    private void streamFrameThrough(UUID siteId, long checkpointSeq, BudgetedMerge merge,
                                    java.util.function.Consumer<ChangeRecord> out,
                                    long[] frameReadNanos) {
        long openedAt = System.nanoTime();
        InputStream opened;
        try {
            opened = checkpointStorage.openFrame(siteId, checkpointSeq);
        } finally {
            frameReadNanos[0] += System.nanoTime() - openedAt;
        }
        try (InputStream frame = opened) {
            TimingInputStream timed = new TimingInputStream(frame);
            try {
                ChangelogCodec.forEach(timed, record -> merge.accept(record, out));
            } finally {
                frameReadNanos[0] += timed.readNanos();
            }
        } catch (FoldTooLargeException | ArtifactSizeLimitExceededException
                | ScratchBudgetExceededException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Failed to read the checkpoint frame of site " + siteId + " at seq " + checkpointSeq, e);
        }
    }

    /**
     * One partition's merge, with the same ceiling on heap the fold has (issue #152) and expressed
     * in the same estimated bytes, so the key means one thing on both paths.
     */
    private static final class BudgetedMerge {

        private final ChangelogMerge merge;
        private final UUID siteId;
        private final long maxBytes;
        private long bytes;
        private long peakBytes;

        private BudgetedMerge(UUID siteId, long maxBytes, int partitions, int partition) {
            this.siteId = siteId;
            this.maxBytes = maxBytes;
            this.merge = new ChangelogMerge(partitions, partition);
        }

        /**
         * Fold one record of the period, then stop the build if the delta no longer fits.
         *
         * <p>Checked after applying rather than before, for the reason {@code BudgetedFold} states:
         * the cost of one record is only known once it has been applied, and one record over the
         * ceiling is not what runs a pod out of memory.</p>
         */
        private void apply(ChangeRecord record) {
            bytes += merge.apply(record);
            peakBytes = Math.max(peakBytes, bytes);
            if (bytes > maxBytes) {
                throw new FoldTooLargeException(siteId, bytes, maxBytes);
            }
        }

        private void accept(ChangeRecord base, java.util.function.Consumer<ChangeRecord> out) {
            merge.accept(base, out);
        }

        private void drain(java.util.function.Consumer<ChangeRecord> out) {
            merge.drain(out);
        }

        /** The largest this partition's delta ever was. */
        private long peakEstimatedBytes() {
            return peakBytes;
        }
    }

    /**
     * Build a site's first checkpoint without folding it into heap (issue #292).
     *
     * <p>Three passes' worth of shape, and the count is what makes this bounded. The segments are
     * streamed <b>once</b> into the reload frame on local disk ({@link BootstrapFrameWriter}), which
     * for an all-{@code INSERT} history is exactly the frame the fold would have re-emitted. Then
     * the snapshots are written from that <b>local</b> file rather than from the fold: one pass to
     * close the decimal envelopes of every table that declares one, and {@code ceil(tables / W)}
     * passes writing {@code W} tables at a time. Nothing here holds more than one record, {@code W}
     * row-group buffers and the repeated-key hash set — none of which grows with the site's rows
     * except the last, at eight bytes each.</p>
     *
     * <p><b>Returns {@code null} to mean "not this way after all"</b>: the wire contract turned out
     * not to hold for this input, nothing durable has been written, and the caller folds instead.
     * That is the whole of the fallback, and it is why the frame is written locally before anything
     * is uploaded.</p>
     */
    private Map<String, Map<String, FoldedRow>> buildFromSnapshotStream(UUID siteId,
                                                                        List<ChangelogSegment> segments,
                                                                        SiteEpoch epoch) {
        long seq = segments.get(segments.size() - 1).getLastSeq();
        prepareScratchDirectory();
        Path frame = createScratchFile(siteId, ".pb.gz");
        // Held for the whole build, unlike the general path's frame lease: the snapshots are written
        // by re-reading this file, so its bytes are on the volume until the last table is done. The
        // checkpoint reserve of issue #193 is what keeps a completed-batch backlog out of them.
        ScratchLease lease = scratchBudget.open(ParquetScratchBudget.CHECKPOINT_FRAME);
        try {
            CheckpointFrameWriter.FrameManifest manifest =
                    streamSegmentsIntoFrame(siteId, segments, frame, lease);
            if (manifest == null) {
                return null;
            }

            // The same order the general path takes, and for the same reasons (issue #153): notice
            // a closing process before the longest single call of the build, and check the epoch
            // with nothing written so a wipe that has already committed is seen before the object
            // is in the bucket rather than after.
            stopIfShuttingDown(siteId);
            epochGuard.requireEpoch(siteId, epoch);
            withFrameCeilingReported(siteId, seq, () ->
                    metrics.timeCheckpointPhase("upload", () ->
                            checkpointStorage.uploadFrame(siteId, seq, frame)));

            int passes = writeSnapshotsFromFrame(siteId, frame, manifest, seq, epoch,
                    SnapshotPass.INCREMENTAL);
            // The measurement the operator needs and the one this path is judged by: the pass count
            // is a function of the table count and delta.checkpoint.snapshot-writers alone, never of
            // the site's rows. A number that grows with the site is this path having gone wrong.
            log.info("Streamed the first checkpoint of site {} at seq {}: {} record(s) across {} "
                    + "table(s), {} pass(es) over the local frame with {} snapshot writer(s), "
                    + "no fold", siteId, seq, manifest.records(), manifest.tables().size(), passes,
                    snapshotWriters);

            epochGuard.inEpoch(siteId, epoch, () -> syncStateService.recordCheckpoint(siteId, seq));
            publishCheckpointRecorded(siteId, seq, epoch);
            // No fold to return, which is the point. Production callers ignore the value; the
            // build's result is the frame, the snapshots and the pointer.
            return Map.of();
        } finally {
            deleteQuietly(frame, "_frame", siteId);
            lease.close();
        }
    }

    /**
     * Stream every segment of the snapshot session into the local frame file.
     *
     * @return what the frame holds, or {@code null} when the session broke the all-{@code INSERT}
     *         contract and the build must fold instead
     */
    private CheckpointFrameWriter.FrameManifest streamSegmentsIntoFrame(UUID siteId,
                                                                       List<ChangelogSegment> segments,
                                                                       Path frame,
                                                                       ScratchLease lease) {
        long startedAt = System.nanoTime();
        try {
            // phase=fold, because this is what replaces it: the segment downloads it always
            // covered, and the writing of the frame that the fold would otherwise have paid for
            // later. phase=download_frame stays absent, as it is on any build with no seed frame.
            CheckpointFrameWriter.FrameManifest manifest;
            try (OutputStream out = new CappedOutputStream(
                            Files.newOutputStream(frame), maxFrameTempBytes, lease);
                    BootstrapFrameWriter writer = BootstrapFrameWriter.open(out)) {
                for (ChangelogSegment segment : segments) {
                    foldSegment(siteId, segment, writer::accept);
                }
                manifest = writer.manifest();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
            }
            return manifest;
        } catch (BootstrapFrameWriter.NotAFullSnapshotException e) {
            // Loud, because it says the client is not sending what the wire contract promises — and
            // the build still succeeds, so nothing else would say so.
            log.warn("The FULL_SNAPSHOT history of site {} does not satisfy the all-INSERT contract, "
                    + "so its first checkpoint is folded the general way instead of streamed: {}",
                    siteId, e.getMessage());
            return null;
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            // The frame's own ceilings, reported exactly as the general path reports them.
            throw reportFrameCeiling(siteId, segments.get(segments.size() - 1).getLastSeq(), e);
        } finally {
            metrics.recordCheckpointPhase("fold", System.nanoTime() - startedAt);
        }
    }

    /**
     * Write each table's Parquet snapshot by re-reading the frame this build just wrote, {@code W}
     * tables at a time (issue #292).
     *
     * <p>Everything about a table's outcome is the general path's: {@link #prepareTable} decides
     * whether it is written at all, {@link #publishTable} uploads and saves it, {@link #failTable}
     * classifies a failure. What differs is only that the rows arrive interleaved, so a table cannot
     * be rendered by iterating a collection of its own.</p>
     */
    private int writeSnapshotsFromFrame(UUID siteId,
                                        Path frame,
                                        CheckpointFrameWriter.FrameManifest manifest,
                                        long seq,
                                        SiteEpoch epoch,
                                        SnapshotPass pass) {
        stopIfShuttingDown(siteId);
        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);

        Map<String, Checkpoint> pending = new LinkedHashMap<>();
        for (String tableName : manifest.tables()) {
            stopIfShuttingDown(siteId);
            Checkpoint checkpoint = prepareTable(siteId, tableName,
                    manifest.rowCounts().getOrDefault(tableName, 0L), seq,
                    pass, epoch, schemas.get(tableName));
            if (checkpoint != null) {
                pending.put(tableName, checkpoint);
            }
        }

        int passes = 0;
        Map<String, Schema> avroSchemas = new LinkedHashMap<>();
        if (closeDecimalEnvelopes(frame, pending.keySet(), schemas, avroSchemas)) {
            passes++;
        }

        List<String> tables = List.copyOf(pending.keySet());
        for (int from = 0; from < tables.size(); from += snapshotWriters) {
            stopIfShuttingDown(siteId);
            writeSnapshotGroup(siteId, frame, seq, epoch, schemas, avroSchemas, pending,
                    tables.subList(from, Math.min(from + snapshotWriters, tables.size())), pass);
            passes++;
        }

        stopIfShuttingDown(siteId);
        if (manifest.tables().isEmpty()) {
            // The empty-fold answer of the general path, for the same reason: the per-table settle
            // lives inside the loop above, so a site whose snapshot carried no record at all would
            // otherwise be revisited nightly forever without ever spending an attempt.
            settleSiteWide(siteId, epoch, pass);
            return passes;
        }
        reapTablesAbsentFrom(siteId, Set.copyOf(manifest.tables()), epoch);
        return passes;
    }

    /**
     * One pass over the local frame that closes every table's decimal envelope.
     *
     * <p>{@code writeParquet} affords two traversals of a table's rows because the general path
     * holds them; here the second traversal would be a second set of passes over the frame, so every
     * table is measured together in this one. Tables that declare no decimal column need no
     * measuring at all, and when none of them does the pass is skipped outright.</p>
     *
     * @param avroSchemas filled with each table's record schema, widened where it was measured
     * @return whether a pass over the frame was actually made
     */
    private boolean closeDecimalEnvelopes(Path frame,
                                          Set<String> tables,
                                          Map<String, TableSchema> schemas,
                                          Map<String, Schema> avroSchemas) {
        Map<String, ParquetCheckpointWriter.DecimalEnvelope> envelopes = new LinkedHashMap<>();
        for (String tableName : tables) {
            Schema declared = ParquetSchemaMapper.toAvroSchema(tableName, schemas.get(tableName));
            ParquetCheckpointWriter.DecimalEnvelope envelope =
                    ParquetCheckpointWriter.decimalEnvelope(declared);
            avroSchemas.put(tableName, declared);
            if (envelope.measuresAnything()) {
                envelopes.put(tableName, envelope);
            }
        }
        if (envelopes.isEmpty()) {
            return false;
        }
        readFrame(frame, record -> {
            ParquetCheckpointWriter.DecimalEnvelope envelope = envelopes.get(record.getTable());
            if (envelope != null) {
                envelope.observe(record.getDataMap());
            }
        });
        envelopes.forEach((tableName, envelope) -> avroSchemas.put(tableName, envelope.widened()));
        return true;
    }

    /**
     * Write one group of tables in a single pass over the local frame.
     *
     * <p>A table that fails mid-pass stops being written and is recorded through {@link #failTable}
     * when the pass ends — the same skip-and-continue contract the folded path has, except that the
     * pass carries the other tables of the group on rather than moving to the next table. A refusal
     * by the shared scratch directory is systemic and ends the build where it happens, as it does
     * there.</p>
     */
    private void writeSnapshotGroup(UUID siteId,
                                    Path frame,
                                    long seq,
                                    SiteEpoch epoch,
                                    Map<String, TableSchema> schemas,
                                    Map<String, Schema> avroSchemas,
                                    Map<String, Checkpoint> pending,
                                    List<String> group,
                                    SnapshotPass pass) {
        Map<String, OpenSnapshot> open = new LinkedHashMap<>();
        try {
            for (String tableName : group) {
                Path file = createScratchFile(siteId);
                ScratchLease lease = scratchBudget.open(ParquetScratchBudget.CHECKPOINT_TABLE);
                open.put(tableName, new OpenSnapshot(file, lease,
                        ParquetCheckpointWriter.openTable(file, tableName, schemas.get(tableName),
                                avroSchemas.get(tableName), maxTempBytes,
                                parquetProperties.rowGroupBytes(), lease)));
            }

            metrics.timeCheckpointPhase("parquet", () -> readFrame(frame, record -> {
                OpenSnapshot snapshot = open.get(record.getTable());
                if (snapshot == null || snapshot.failure != null) {
                    return;
                }
                try {
                    snapshot.writer.write(record.getDataMap());
                } catch (RuntimeException e) {
                    if (isScratchBudgetRefusal(e)) {
                        throw scratchDirectoryFull(siteId, record.getTable(), e);
                    }
                    snapshot.failure = e;
                }
            }));

            for (Map.Entry<String, OpenSnapshot> entry : open.entrySet()) {
                OpenSnapshot snapshot = entry.getValue();
                try {
                    snapshot.writer.close();
                    snapshot.closed = true;
                } catch (RuntimeException e) {
                    if (isScratchBudgetRefusal(e)) {
                        throw scratchDirectoryFull(siteId, entry.getKey(), e);
                    }
                    if (snapshot.failure == null) {
                        snapshot.failure = e;
                    }
                }
            }

            for (Map.Entry<String, OpenSnapshot> entry : open.entrySet()) {
                String tableName = entry.getKey();
                OpenSnapshot snapshot = entry.getValue();
                Checkpoint checkpoint = pending.get(tableName);
                try {
                    if (snapshot.failure != null) {
                        throw snapshot.failure;
                    }
                    ParquetCheckpointWriter.warnDegraded(tableName, snapshot.writer.tally());
                    publishTable(siteId, checkpoint, tableName, seq, snapshot.file,
                            snapshot.writer.tally(), epoch);
                } catch (RuntimeException e) {
                    failTable(siteId, checkpoint, tableName, pass, epoch, e);
                }
            }
        } finally {
            open.forEach((tableName, snapshot) -> {
                if (!snapshot.closed) {
                    // Best effort: the group is unwinding on something systemic, and a writer left
                    // open would keep its scratch file undeletable on the platforms that care.
                    try {
                        snapshot.writer.close();
                    } catch (RuntimeException ignored) {
                        // the file is deleted next, and the failure that is unwinding is the story
                    }
                }
                deleteQuietly(snapshot.file, tableName, siteId);
                snapshot.lease.close();
            });
        }
    }

    /** One table's open snapshot file within a group pass. */
    private static final class OpenSnapshot {

        private final Path file;
        private final ScratchLease lease;
        private final ParquetCheckpointWriter.OpenTable writer;
        private RuntimeException failure;
        private boolean closed;

        private OpenSnapshot(Path file, ScratchLease lease, ParquetCheckpointWriter.OpenTable writer) {
            this.file = file;
            this.lease = lease;
            this.writer = writer;
        }
    }

    /** Read the locally written frame back, record by record. */
    private static void readFrame(Path frame, java.util.function.Consumer<ChangeRecord> consumer) {
        try (InputStream in = Files.newInputStream(frame)) {
            ChangelogCodec.forEach(in, consumer);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the local checkpoint frame " + frame, e);
        }
    }

    private Map<String, Map<String, FoldedRow>> materialize(UUID siteId,
                                                            Map<String, Map<String, FoldedRow>> state,
                                                            List<ChangelogSegment> newSegments,
                                                            SiteEpoch epoch) {
        long seq = newSegments.get(newSegments.size() - 1).getLastSeq();

        // The frame goes first, before a single snapshot object exists at the new seq (issue
        // #153). It is the one artifact of a build that cannot be skipped, so it is also the one
        // that decides whether the build can finish at all — writing it last meant every abort was
        // paid for with a full set of per-table uploads that the pointer then never adopted. Those
        // objects are unreferenced the moment the next build writes its own (a `checkpoints` row is
        // one per table and carries a single key), and at the time nothing but a site wipe swept
        // `checkpoints/{siteId}/` (#118; the daily sweep of #158 collects them now). Since crossing
        // the ceiling is deterministic for the same fold, that was one orphaned generation per
        // nightly tick, indefinitely.
        //
        // The epoch is checked first, with nothing to write. Writing and uploading the frame is the
        // longest stretch of a build that touches no row, and moving it to the front would
        // otherwise mean the build's first contact with the site_sync_state lock came *after* it
        // rather than before — a wipe or re-baseline that had already committed would be noticed
        // only once the frame object was in the bucket. It does not make the upload atomic (a wipe
        // committing during it still leaves an orphan the next wipe sweeps), but it keeps the
        // window no wider than it was when writeSnapshots ran first.
        // Cheapest possible place to notice the process is going: the frame upload is the longest
        // single call of a build, and starting a multi-GiB PUT that will be cut off mid-flight
        // leaves an orphan for nothing.
        stopIfShuttingDown(siteId);
        epochGuard.requireEpoch(siteId, epoch);
        uploadFrame(siteId, seq, state);
        writeSnapshots(siteId, state, seq, SnapshotPass.INCREMENTAL, epoch);

        epochGuard.inEpoch(siteId, epoch, () -> syncStateService.recordCheckpoint(siteId, seq));
        // The single choke point every checkpoint build passes through, scheduled or forced. The
        // Bit BI auto-reinit after a history wipe (issue #89) hangs off it, because this is the
        // first moment post-wipe at which there are checkpoint seqs to freeze as SQL baselines.
        // The checkpoint is already durable by now, so a listener's failure must not be allowed to
        // fail the build behind it — that would freeze the pointer and stop retention.
        //
        // The publish is deliberately outside the guard's transaction: DeltaWipeReinitListener is a
        // synchronous listener in its own REQUIRES_NEW transaction and its clearWipePending would
        // block on the site_sync_state row lock the suspended guard transaction still holds. That
        // leaves a gap in which a wipe can commit, so the event carries the epoch this build folded
        // and the listener re-checks it (issue #142).
        publishCheckpointRecorded(siteId, seq, epoch);
        return state;
    }

    /**
     * Announce the recorded checkpoint. The checkpoint is already durable, so a listener's failure
     * must not fail the build behind it — that would freeze the pointer and stop retention.
     */
    private void publishCheckpointRecorded(UUID siteId, long seq, SiteEpoch epoch) {
        try {
            eventPublisher.publishEvent(new CheckpointRecordedEvent(siteId, seq, epoch));
        } catch (RuntimeException e) {
            log.error("A checkpoint listener failed for site {} at seq {}; the checkpoint itself "
                    + "is committed", siteId, seq, e);
        }
    }

    /**
     * Persist the new all-INSERT frame so the next build seeds from it and earlier segments can be
     * pruned. Same file-backed path as the snapshot (issue #126): one record at a time into a
     * scratch file, then {@code RequestBody.fromFile} — never a collected List and never a gzip
     * {@code byte[]}. The site fold itself stays in heap.
     *
     * <p>Its own ceiling, not the snapshot's (issue #138). The two files share a directory but not
     * a failure mode: an oversized table is skipped and repaired by the next build, while an
     * oversized frame ends the build, because the frame is the next incremental seed. One key for
     * both meant the value had to be set for the harsher of the two, which left it above the
     * deployed scratch volume and made a kubelet eviction the first thing to happen.</p>
     *
     * <p>The file is uploaded and deleted here rather than kept open across the snapshot loop, so
     * the checkpoint path holds one scratch file at a time. Batch writers cannot take the last
     * {@code max-frame-temp-bytes} of the directory budget (issue #193), which is what keeps a
     * completed-batch backlog from starving this write.</p>
     */
    private void uploadFrame(UUID siteId, long seq, Map<String, Map<String, FoldedRow>> state) {
        prepareScratchDirectory();
        Path frame = createScratchFile(siteId, ".pb.gz");
        // Closed after the delete, not after the upload: the bytes are on the volume until the file
        // is gone, and releasing the lease earlier would let another writer be told there is room
        // that does not exist yet. Released even when the delete failed — see the same finally in
        // BatchParquetFinalizationService for why holding it would be the worse, and permanent,
        // error.
        ScratchLease lease = scratchBudget.open(ParquetScratchBudget.CHECKPOINT_FRAME);
        try {
            metrics.timeCheckpointPhase("upload", () -> {
                try (OutputStream out = new CappedOutputStream(
                        Files.newOutputStream(frame), maxFrameTempBytes, lease)) {
                    ChangelogCodec.write(CheckpointFrame.records(state), out);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
                }
                checkpointStorage.uploadFrame(siteId, seq, frame);
            });
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            throw reportFrameCeiling(siteId, seq, e);
        } finally {
            deleteQuietly(frame, "_frame", siteId);
            lease.close();
        }
    }

    /**
     * Run the frame's write-and-upload with its two ceilings reported the way #138 and #150 report
     * them — shared by the folded path and by the streaming bootstrap path (issue #292), which
     * writes and uploads the same file in two separate steps.
     */
    private void withFrameCeilingReported(UUID siteId, long seq, Runnable work) {
        try {
            work.run();
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            throw reportFrameCeiling(siteId, seq, e);
        }
    }

    /**
     * Log (and, for the deterministic one, count) a reload frame that could not be written, and
     * hand the exception back for the caller to throw. Either way the build ends: the frame is the
     * next incremental seed and there is nothing to fall back on.
     */
    private RuntimeException reportFrameCeiling(UUID siteId, long seq, RuntimeException e) {
        if (e instanceof ArtifactSizeLimitExceededException) {
            // Both ceilings raise the same exception with the same "temp-file limit of N bytes"
            // text, and the per-table one is reported by its own counter — say which guard this
            // was and name the key, or the operator has only a byte count to go on. Rethrown
            // unchanged: an oversized frame still ends the build.
            //
            // Counted as well as logged (issue #153). The failure is deterministic for a given
            // fold, so it recurs on every tick with the pointer — and therefore retention — frozen
            // in place; a log line is not something an alert can be built on, and the symptom an
            // operator would otherwise notice first is an unbounded segment table.
            //
            // Logged before it is counted: the counter validates its reason and throws on an
            // unknown one (the same contract as checkpointTableUnmaterialized, and a programming
            // error either way), which would otherwise replace this exception *and* swallow the
            // only line naming the site and the key.
            log.error("The checkpoint reload frame for site {} at seq {} crossed "
                    + "delta.checkpoint.max-frame-temp-bytes ({} bytes) — the build is abandoned "
                    + "before any snapshot was written, so nothing durable changed: the per-table "
                    + "keys and last_checkpoint_seq stay where they were. Retention is frozen at "
                    + "that pointer and the next tick will fail identically, because the fold has "
                    + "not changed. Raise that key (and the scratch volume behind it) rather than "
                    + "the per-table ceiling",
                    siteId, seq, maxFrameTempBytes);
            metrics.checkpointBuildAborted("frame_too_large");
            return e;
        }
        if (e instanceof ScratchBudgetExceededException) {
            // The frame's existing failure mode — the build ends, because the frame is the next
            // incremental seed and there is nothing to fall back on. What it is deliberately NOT is
            // a fifth value on delta.checkpoint.builds.aborted: every value there is a refusal that
            // never repairs itself (#153), and this one clears the moment the batch workers holding
            // the directory finish. delta.parquet.scratch.refused{writer=checkpoint_frame} already
            // counted it inside the budget.
            //
            // A completed-batch backlog cannot take the reserved share (#193): batch writers stop
            // at max-scratch-bytes minus this frame ceiling. Seeing this with the directory budget
            // on is therefore a reserve of zero, a misconfiguration, or a checkpoint writer
            // competing with itself — not the operator's backlog.
            log.error("The checkpoint reload frame for site {} at seq {} could not be written "
                    + "because the shared Parquet scratch directory was full — the build is "
                    + "abandoned before any snapshot was written, so nothing durable changed and "
                    + "the next tick tries again. This is contention, not a fact about the site: "
                    + "raise delta.parquet.max-scratch-bytes (and the volume behind it), or lower "
                    + "delta.batch-parquet.max-concurrent", siteId, seq, e);
            return e;
        }
        return e;
    }

    /**
     * Every scratch file of this build goes through the same directory, one at a time. Creating it
     * is systemic, not per-artifact: if it fails, nothing can be materialized this build, so let it
     * fail the build loudly instead of counting every table as its own skip.
     */
    private void prepareScratchDirectory() {
        try {
            Files.createDirectories(tempDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot prepare the checkpoint scratch directory " + tempDirectory, e);
        }
    }

    /**
     * Write (or retry) each table's Parquet snapshot at {@code seq}.
     *
     * <p>{@link SnapshotPass#INCREMENTAL} advances seq and detaches a failed key.
     * {@link SnapshotPass#RETRY_MISSING} and {@link SnapshotPass#FORCE} stay on the recorded
     * pointer and keep a last-good key if the rewrite fails.</p>
     *
     * <p>Every row write goes through {@link CheckpointEpochGuard}, so a build whose site was wiped
     * or re-baselined mid-flight stops here instead of re-inserting the rows that just went.</p>
     */
    private void writeSnapshots(UUID siteId,
                                Map<String, Map<String, FoldedRow>> state,
                                long seq,
                                SnapshotPass pass,
                                SiteEpoch epoch) {
        stopIfShuttingDown(siteId);
        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);
        prepareScratchDirectory();

        // Per-segment delta Parquet is event-driven (Task 8, DeltaEgressService); the checkpoint
        // additionally materializes the full per-table load as typed Parquet (the only format V2
        // produces since issue #113) plus the frame seed.
        state.forEach((tableName, rows) -> {
            // Between tables, not only inside the catch: once the context is closing every
            // remaining table would fail identically, and each failure is another opportunity to
            // mistake "this process is ending" for "this table cannot be materialized".
            stopIfShuttingDown(siteId);
            Checkpoint checkpoint =
                    prepareTable(siteId, tableName, rows.size(), seq, pass, epoch, schemas.get(tableName));
            if (checkpoint == null) {
                return;
            }
            TableSchema tableSchema = schemas.get(tableName);

            // One table at a time: write this table's rows to disk, hand the file to S3, drop
            // it. Materialization therefore costs one row-group buffer and one scratch file at
            // a time instead of one encoded Parquet per table. The new frame (issue #126) went
            // through the same directory just before this loop and its file is already gone, so
            // "one at a time" covers the whole build and not only its snapshot half.
            //
            // One table's coercion failure (schema drift, bad value) must not abort the whole
            // build: the pointer would freeze, retention would stop, and segments would grow
            // unbounded. Skip that table and keep going — the same skip-and-continue contract
            // as DeltaEgressService.
            Path snapshot = createScratchFile(siteId);
            ScratchLease lease = scratchBudget.open(ParquetScratchBudget.CHECKPOINT_TABLE);
            try {
                // Captured rather than returned through timeCheckpointPhase so the phase keeps
                // being timed as a Runnable: which overload times a phase is incidental to this
                // change, and CheckpointServiceTest pins the shape as part of the #111 phase guard.
                java.util.concurrent.atomic.AtomicReference<DecimalDegradeTally> nonFinite = new java.util.concurrent.atomic.AtomicReference<>();
                metrics.timeCheckpointPhase("parquet", () ->
                        nonFinite.set(ParquetCheckpointWriter.writeParquet(snapshot, tableName, tableSchema,
                                dataRows(rows), maxTempBytes, parquetProperties.rowGroupBytes(),
                                lease)));

                publishTable(siteId, checkpoint, tableName, seq, snapshot, nonFinite.get(), epoch);
            } catch (RuntimeException e) {
                failTable(siteId, checkpoint, tableName, pass, epoch, e);
            } finally {
                // The scratch file is this build's litter whichever way the table ended: kept,
                // it would fill the node one checkpoint cycle at a time.
                deleteQuietly(snapshot, tableName, siteId);
                lease.close();
            }
        });

        // After the loop, not before it. The rows this build is about to write exist by now, so
        // `checkpoints` is never transiently empty for a site that has tables — and a reader
        // landing in that window would not be a cosmetic problem: CheckpointFileQueryService keys
        // its pre-Delta fallback on the site having no checkpoint rows at all, and would hand a
        // Bit BI client historical uploaded CSVs as if they were its current baseline.
        stopIfShuttingDown(siteId);
        if (state.isEmpty()) {
            // Every row would be reaped, and the reap must never empty a site (see below) — so
            // without this the site would be folded every night forever and never spend an
            // attempt, because the per-table settle lives inside the loop above and an empty fold
            // never enters it. That is the unbounded retry this ticket removes, minus even the
            // visibility. Settle it site-wide instead, exactly as a history_gone abort does: the
            // rows drain to the cap, the site stops naming itself, and
            // delta.checkpoint.tables.given-up carries it from then on.
            settleSiteWide(siteId, epoch, pass);
            return;
        }
        reapTablesAbsentFromTheFold(siteId, state, epoch);
    }

    /**
     * The {@code checkpoints} row bookkeeping that precedes a table's Parquet, shared by the folded
     * and the streaming path (issue #292).
     *
     * <p>The order matters and is the one {@code writeSnapshots} has always had: the dedicated-retry
     * skips first, then the row (created or advanced to this seq and row count), then a forced
     * rebuild's re-arm, and only then the declared schema — a table with no schema still gets its
     * row, so the hole is visible rather than absent.</p>
     *
     * @return the row to write this table into, or {@code null} when the table must be skipped —
     *         already materialized on a dedicated retry, past the retry cap, or missing its schema
     *         (which is reported and charged here, since there is nothing left to attempt)
     */
    private Checkpoint prepareTable(UUID siteId, String tableName, long rowCount, long seq,
                                    SnapshotPass pass, SiteEpoch epoch, TableSchema tableSchema) {
        if (pass == SnapshotPass.RETRY_MISSING) {
            Optional<Checkpoint> existing =
                    checkpointRepository.findBySiteIdAndTableName(siteId, tableName);
            if (existing.isPresent() && existing.get().getS3KeyParquet() != null) {
                return null;
            }
            // The bound on the retry (issue #149). A row that has spent its attempts is not
            // going to materialize tonight either: the causes that survive this many nights —
            // a schema the client never submits, a value Parquet cannot render — are not the
            // kind that pass with time. Only the *dedicated* retry stops; an incremental build
            // below still writes this table with the rest of its fold.
            if (existing.isPresent()
                    && existing.get().hasGivenUpMaterializing(
                            retryProperties.maxMaterializeAttempts())) {
                return null;
            }
        }
        Checkpoint checkpoint = findOrCreate(siteId, tableName, seq, rowCount);
        if (pass == SnapshotPass.FORCE) {
            // The operator's exit from the cap, and the reason giving up is not a dead end:
            // asking for a rebuild says the cause has been dealt with, so the row goes back
            // into the nightly population whether this attempt succeeds or not.
            checkpoint.rearmMaterialization();
        }

        if (tableSchema == null) {
            // Parquet needs the declared schema, and there is no CSV left to fall back on: this
            // table simply has nothing to download until a schema arrives. The client is
            // required to SubmitSchema before its first session, so this means the site is
            // misconfigured — count it so the hole is visible rather than silent.
            // Write first, then report — the reverse of failTable, and deliberately so: there is
            // no cause to preserve here, and an epoch refusal must leave the meter alone. A
            // discarded build has no tables to report a hole for.
            if (abandonStaleSnapshot(checkpoint, pass)) {
                checkpoint.recordFailedMaterialization();
                epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
            }
            metrics.checkpointTableUnmaterialized("no_schema");
            log.warn("No declared schema for table {} of site {} — checkpoint row recorded "
                    + "without a downloadable artifact (the client must SubmitSchema)",
                    tableName, siteId);
            return null;
        }
        return checkpoint;
    }

    /**
     * Publish one written snapshot: upload it, save the row through the epoch guard, then count the
     * cells that had to be degraded (issue #292 — shared by both snapshot paths).
     */
    private void publishTable(UUID siteId, Checkpoint checkpoint, String tableName, long seq,
                              Path snapshot, DecimalDegradeTally nonFinite, SiteEpoch epoch) {
        metrics.timeCheckpointPhase("upload", () ->
                checkpoint.attachParquet(checkpointStorage.uploadParquet(
                        siteId, tableName, seq, snapshot)));
        epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        // After the epoch guard, not merely after the upload (review round 3): a wipe or
        // re-baseline landing mid-build makes the guard throw and discards everything the
        // build produced, so counting earlier credited cells to an artifact that was never
        // published -- and the next build re-renders and counts them again.
        metrics.unrepresentableDecimalsDegraded(nonFinite.nonFiniteCount(), false);
        metrics.unrepresentableDecimalsDegraded(nonFinite.malformedCount(), true);
    }

    /**
     * Classify one table's failure: end the build, or record it against the table and carry on
     * (issue #292 — shared by both snapshot paths).
     */
    private void failTable(UUID siteId, Checkpoint checkpoint, String tableName, SnapshotPass pass,
                           SiteEpoch epoch, RuntimeException e) {
        if (e instanceof CheckpointEpochGuard.EpochChangedException) {
            // A replaced baseline is not a fact about this table: nothing this build produced
            // may be published, so it must escape the per-table skip below and end the build.
            throw e;
        }
        if (e instanceof BuildEndedByShutdownException) {
            throw e;
        }
        // A full scratch directory is a SYSTEMIC scratch failure, so it ends the build —
        // the same answer prepareScratchDirectory() gives an unusable directory, for the
        // reason stated there: skipping would detach every last-good snapshot while the
        // pointer advanced. Skipping this one table looks gentler and is not (issue #150,
        // review round 2). The pointer would move to the new seq with this table's row left
        // at the old one, and nothing would mark it as owing a rewrite: the nightly
        // rematerialize keys on a NULL s3_key_parquet, so a site that then goes quiet
        // serves a snapshot silently missing every change in between, indefinitely, while
        // retention has already pruned the segments below the new pointer. Detaching
        // instead would fix the retry and 404 a healthy artifact for a neighbour's disk
        // use. And on a site's FIRST build, findOrCreate's row is not saved either, so a
        // refusal across every table leaves `checkpoints` empty with the pointer advanced —
        // which CheckpointFileQueryService reads as "not a Delta site yet" and answers with
        // the historical uploaded CSVs as if they were the current baseline.
        //
        // Ending the build has none of those: no object, no row, no pointer, no attempt
        // spent, retention frozen for one night and the whole seq redone on the next tick.
        // Deliberately NOT on delta.checkpoint.builds.aborted (#153's tag values never
        // repair themselves); delta.parquet.scratch.refused{writer=checkpoint_table}
        // counted it inside the budget, and issue #193 tracks the asymmetry with the
        // completed-batch side, which degrades one artifact at a time.
        if (isScratchBudgetRefusal(e)) {
            throw scratchDirectoryFull(siteId, tableName, e);
        }
        // A failure seen while the context is closing is a fact about the process, not
        // about this table (issue #162). The S3Client and the DataSource are destroyed
        // right after ContextClosedEvent is published, so every call from here on fails
        // with an exception that reads exactly like a broken table. Recording it would
        // detach a healthy snapshot on an advancing seq, and the row would 404 for Bit BI
        // and Parquet Export until the next nightly rematerialize.
        if (shutdownSignal.isShuttingDown()) {
            throw new BuildEndedByShutdownException(siteId, tableName, e);
        }
        // Report the cause first: the detach below goes through the epoch guard, which
        // throws rather than returns when the site was wiped mid-build, and this table's
        // actual failure (schema drift, an oversized table) would leave no trace at all.
        metrics.checkpointTableUnmaterialized("parquet_failed");
        log.warn("Checkpoint Parquet failed for table {} of site {} — the table has no "
                + "artifact this build (check the declared schema against the data, or "
                + "delta.checkpoint.max-temp-bytes against the table's size)",
                tableName, siteId, e);
        // When seq advanced, the previous key would sit beside a newer seq and be served
        // as its snapshot — detach it. On a same-seq rematerialize the last-good object
        // is still at that key; keep the row pointing at it.
        if (abandonStaleSnapshot(checkpoint, pass)) {
            // The row ends this build owing a snapshot, so the attempt is spent (issue
            // #149). A failure that leaves a still-valid last-good key is deliberately not
            // counted: the retry exists for rows with nothing to serve, and charging one
            // to a healthy row would eventually retire a table nobody is waiting on.
            checkpoint.recordFailedMaterialization();
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        }
    }

    /**
     * Charge (or re-arm) every unmaterialized row of a site for an outcome that belongs to the
     * whole build rather than to any one table.
     *
     * <p>A forced rebuild re-arms where a scheduled one spends: it is the operator asserting the
     * cause has been dealt with, and it is the documented recovery from both states that reach
     * here, so it must not be the fastest way to exhaust the retry it is meant to restore.</p>
     */
    private void settleSiteWide(UUID siteId, SiteEpoch epoch, SnapshotPass pass) {
        if (pass == SnapshotPass.FORCE) {
            rearmEveryUnmaterializedTable(siteId, epoch);
        } else {
            spendAnAttemptOnEveryRetryableTable(siteId, epoch);
        }
    }

    /**
     * Delete the checkpoint rows of tables the site no longer has (issue #149).
     *
     * <p>The fold is the whole of the site's state at this build's seq — the frame is a complete
     * all-INSERT snapshot and every surviving segment above it is folded on top — so a table with a
     * {@code checkpoints} row and no entry in the fold is a table that no longer exists. It got
     * there by having its last row {@code DELETE}d: the build that saw the deletion still had the
     * (now empty) table in its fold and wrote it, but {@link CheckpointFrame} emits no record for a
     * table with no rows, so the frame it wrote never mentions the table again.</p>
     *
     * <p>Before this, nothing could clear such a row. Both snapshot passes iterate the fold, so the
     * loop never reached the table; only a wipe or a re-baseline deletes checkpoint rows; and with
     * the row's key still null it named its site on the tick's work list every night, forever, for
     * work no build — not even a forced rebuild — could do. Reaping it is the exit, and it is the
     * truthful answer for a row that <em>did</em> keep a key too: that snapshot describes a table
     * the site dropped, and serving it as current would be a lie.</p>
     *
     * <p><b>Promptly for the first, eventually for the second.</b> This runs inside
     * {@code writeSnapshots}, which a scheduled build reaches only when it has work — new segments,
     * or a still-retryable unmaterialized row (the probe in {@link #build} returns before the fold
     * otherwise, which is the whole point of issue #149's cheap idle visit). A dropped table whose
     * row kept a live key therefore survives on a site that is completely idle, until the next build
     * with any work at all, or a forced rebuild. That is deliberate: making the reap its own reason
     * to fold a whole site nightly would reintroduce the cost this ticket removed, for a stale
     * listing entry rather than a missing artifact.</p>
     *
     * <p>The object the row named is left in {@code checkpoints/{siteId}/} as an orphan, which is
     * what every superseded snapshot has always been there (the row carries one key and each build
     * replaces it): a site wipe and {@code DeltaS3OrphanSweeper} (#158) both collect it. Deleting it
     * here would put an S3 round trip on the build for no new guarantee.</p>
     *
     * <p>Deletes run through the epoch guard like every other write of a build, so a wipe or a
     * re-baseline committing mid-build ends the build instead of deleting rows of a baseline it
     * knows nothing about.</p>
     *
     * <p><b>Never called with an empty fold.</b> Every row would go, and "this site has no
     * checkpoint rows" is a load-bearing state elsewhere: {@code CheckpointFileQueryService} reads
     * it as "not a Delta site yet" and falls back to the pre-Delta uploaded CSVs, which is exactly
     * what it must not hand a Bit BI client as a current baseline. A site whose every table was
     * emptied is settled site-wide by the caller instead — see {@link #settleSiteWide}.</p>
     */
    private void reapTablesAbsentFromTheFold(UUID siteId,
                                             Map<String, Map<String, FoldedRow>> state,
                                             SiteEpoch epoch) {
        reapTablesAbsentFrom(siteId, state.keySet(), epoch);
    }

    /** See {@link #reapTablesAbsentFromTheFold}; the streaming path knows its tables by name only. */
    private void reapTablesAbsentFrom(UUID siteId, Set<String> tables, SiteEpoch epoch) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (tables.contains(checkpoint.getTableName())) {
                continue;
            }
            log.info("Dropping the checkpoint row for table {} of site {}: the table is absent from "
                    + "the folded state, so its last row was deleted at the source",
                    checkpoint.getTableName(), siteId);
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.deleteById(checkpoint.getId()));
        }
    }

    /**
     * End the build: the shared scratch directory had no room for this table's snapshot.
     *
     * <p>Returns the exception rather than throwing it, so the call site reads
     * {@code throw scratchDirectoryFull(...)} and the compiler can see the branch ends. A
     * completed-batch backlog cannot take the reserved share (#193); seeing this with the
     * directory budget on is a reserve of zero or a misconfiguration, not the operator's
     * backlog.</p>
     */
    private static RuntimeException scratchDirectoryFull(UUID siteId, String tableName,
                                                        RuntimeException error) {
        log.error("The checkpoint snapshot for table {} of site {} could not be written because the "
                + "shared Parquet scratch directory was full — the build is abandoned, so nothing "
                + "durable changed: the per-table keys and last_checkpoint_seq stay where they were "
                + "and the next tick tries again. This is contention, not a fact about the site: "
                + "raise delta.parquet.max-scratch-bytes (and the volume behind it), or lower "
                + "delta.batch-parquet.max-concurrent", tableName, siteId, error);
        return error;
    }

    /**
     * Is this failure the shared scratch directory refusing room (issue #150)?
     *
     * <p>The whole cause chain, as {@code DeltaParquetWriter.failure()} already walks it for the
     * per-file ceiling's exception. Nothing wraps this one today, so a direct {@code instanceof}
     * would work — but a future wrap would be silently <em>worse</em> here than on the batch path:
     * the refusal would fall through to {@code parquet_failed}, which detaches a healthy last-good
     * snapshot on an advancing seq and spends a materialize attempt against
     * {@code delta.checkpoint.tables.given-up} (raised in review).</p>
     */
    private static boolean isScratchBudgetRefusal(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScratchBudgetExceededException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /**
     * Detach the snapshot key only when keeping it would lie (seq moved, or there was never a
     * key). A same-seq rematerialize that fails must leave a still-valid last-good key in place.
     *
     * @return {@code true} when the row changed and must be saved
     */
    private static boolean abandonStaleSnapshot(Checkpoint checkpoint, SnapshotPass pass) {
        if (pass == SnapshotPass.INCREMENTAL || checkpoint.getS3KeyParquet() == null) {
            checkpoint.detachParquet();
            return true;
        }
        return false;
    }

    /**
     * End the build if the application has begun to close.
     *
     * <p>Thrown rather than returned so it escapes the per-table catch below: a build ending with
     * the process must publish nothing, not skip one table and carry on to the next.</p>
     */
    private void stopIfShuttingDown(UUID siteId) {
        if (shutdownSignal.isShuttingDown()) {
            throw new BuildEndedByShutdownException(siteId, null, null);
        }
    }

    /**
     * The site's baseline was replaced while this build was running, so it published nothing
     * (issues #136 and #142).
     *
     * <p>Thrown rather than returned as an empty fold, for the reason #157 and #162 are: a caller
     * cannot tell an empty fold from a finished build. {@code CheckpointScheduler} logs it and moves
     * to the next site, exactly as it did when this was a silent empty return; the forced path needs
     * it because reporting a discarded build as {@code COMPLETED} paints a green "Rebuilt" chip for
     * a rebuild that published nothing (issue #186). Not a failure of the site and not on
     * {@code delta.checkpoint.builds.aborted}: a wipe and a re-baseline are routine operator and
     * client actions, and the build after them starts from the new baseline.</p>
     */
    public static final class BuildDiscardedException extends RuntimeException {

        BuildDiscardedException(UUID siteId, String reason) {
            super("The checkpoint build for site " + siteId + " was discarded: " + reason);
        }
    }

    /**
     * A <b>forced</b> rebuild of a site that has no seed frame and no changelog (issue #186).
     *
     * <p>Only the forced pass throws: for the nightly tick this is the ordinary quiet visit to a
     * site named by an unmaterialized checkpoint row, while a forced rebuild is a question somebody
     * asked, and "rebuilt" is not a truthful answer when there was nothing to rebuild from. Nothing
     * is written and nothing is counted.</p>
     */
    public static final class NothingToRebuildException extends RuntimeException {

        NothingToRebuildException(UUID siteId) {
            super("Site " + siteId + " has no checkpoint frame and no changelog segments, so there "
                    + "is nothing to rebuild its checkpoints from");
        }
    }

    /**
     * S3 would not say whether the site's seed frame exists, so this build did nothing (issue #157).
     *
     * <p>Public and thrown, unlike its shutdown sibling, because two callers must tell it apart from
     * a build that finished: {@code CheckpointScheduler} logs it and moves to the next site, while
     * {@code DeltaCheckpointRebuildService} keeps the operator's {@code rebuild_requested} flag
     * rather than reporting a rebuild that never ran. Nothing durable changed — no fold, no upload,
     * no row, no attempt spent — and the next tick asks S3 the same question again.</p>
     */
    public static final class FramePresenceUnknownException extends RuntimeException {

        FramePresenceUnknownException(UUID siteId, long checkpointSeq) {
            super("S3 would not say whether checkpoint frame@" + checkpointSeq + " of site "
                    + siteId + " exists (read denied); the build was skipped and nothing was "
                    + "recorded — see delta.s3.read-denied");
        }
    }

    /**
     * The site's folded state grew past {@code delta.checkpoint.max-fold-bytes} (issue #152).
     *
     * <p>Public, and thrown rather than swallowed into an empty fold, for the reason the two
     * siblings above are: a caller must be able to tell "this build refused" from "this build had
     * nothing to do". {@code CheckpointScheduler} logs it and moves to the next site;
     * {@code DeltaCheckpointRebuildService} reports the forced rebuild as failed and releases its
     * flag, so an operator can ask again once the budget (or the pod) has been raised.</p>
     *
     * <p>Nothing durable was written when this is thrown: it can only happen during the fold, which
     * precedes the frame upload — the build's first side effect since #153.</p>
     */
    public static final class FoldTooLargeException extends RuntimeException {

        private final long estimatedBytes;
        private final long budgetBytes;

        FoldTooLargeException(UUID siteId, long estimatedBytes, long budgetBytes) {
            super("The checkpoint fold for site " + siteId + " reached an estimated " + estimatedBytes
                    + " bytes of heap, past the " + budgetBytes
                    + "-byte delta.checkpoint.max-fold-bytes budget; the build was abandoned before "
                    + "anything was written");
            this.estimatedBytes = estimatedBytes;
            this.budgetBytes = budgetBytes;
        }

        /** Estimated retained heap of the fold when it was refused. */
        public long estimatedBytes() {
            return estimatedBytes;
        }

        /** The budget it crossed, as resolved from {@code delta.checkpoint.max-fold-bytes}. */
        public long budgetBytes() {
            return budgetBytes;
        }
    }

    /**
     * The build stopped because this application context is closing — never a verdict on a table.
     *
     * <p>Private because it must not be caught anywhere but in {@link #run}: every other handler in
     * this class exists to turn a failure into a durable conclusion, which is precisely what this
     * one must not become.</p>
     */
    private static final class BuildEndedByShutdownException extends RuntimeException {

        private BuildEndedByShutdownException(UUID siteId, String tableName, Throwable cause) {
            super("The checkpoint build for site " + siteId
                    + (tableName == null ? "" : " (table " + tableName + ")")
                    + " ended because the application is shutting down", cause);
        }
    }

    /**
     * Does this site still owe a rematerialize that the nightly pass is allowed to attempt?
     *
     * <p>"Unmaterialized" alone is not the question (issue #149): a row that has spent its attempts
     * is unmaterialized and will stay that way, and answering yes for it is what made an idle visit
     * pay a frame download and a whole-site fold every night for work the pass would then skip.</p>
     */
    private boolean hasRetryableUnmaterializedTables(UUID siteId) {
        int maxAttempts = retryProperties.maxMaterializeAttempts();
        return checkpointRepository.findBySiteId(siteId).stream()
                .anyMatch(checkpoint -> checkpoint.getS3KeyParquet() == null
                        && !checkpoint.hasGivenUpMaterializing(maxAttempts));
    }

    /**
     * A lazily iterated view of one table's folded rows — the writer traverses it (twice at most,
     * for the decimal envelope) instead of receiving a materialized copy of the state.
     */
    private static Iterable<Map<String, Value>> dataRows(Map<String, FoldedRow> rows) {
        return () -> rows.values().stream().map(FoldedRow::data).iterator();
    }

    /**
     * Create this artifact's scratch file. A failure here says the scratch directory itself is
     * unusable (gone, read-only, out of inodes) — it is not a fact about this table and it would
     * hit every table of every site alike. Skipping per table would detach every last-good
     * snapshot while the pointer still advanced. A later rematerialize (issue #128) can restore
     * a per-table hole, but a systemic scratch failure must not throw away the last downloadable
     * snapshots first. Fail the build instead, leaving the pointer and keys where they were so the
     * next run redoes everything; {@code CheckpointScheduler} catches per site, so one site's
     * failure does not stop the sweep.
     *
     * <p>A failure <em>during</em> the write stays a per-table skip (the general catch above), so a
     * single oversized or unrenderable table cannot freeze the pointer and stop retention.</p>
     */
    private Path createScratchFile(UUID siteId) {
        return createScratchFile(siteId, ".parquet");
    }

    private Path createScratchFile(UUID siteId, String suffix) {
        try {
            return Files.createTempFile(tempDirectory,
                    ParquetScratch.CHECKPOINT_PREFIX + siteId + "-", suffix);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot create a checkpoint scratch file in " + tempDirectory, e);
        }
    }

    private static void deleteQuietly(Path snapshot, String tableName, UUID siteId) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException e) {
            log.warn("Could not delete the temporary checkpoint snapshot {} of table {} for site {}",
                    snapshot, tableName, siteId, e);
        }
    }

    private Checkpoint findOrCreate(UUID siteId, String tableName, long seq, long rowCount) {
        return checkpointRepository.findBySiteIdAndTableName(siteId, tableName)
                .map(existing -> {
                    existing.update(seq, rowCount);
                    return existing;
                })
                .orElseGet(() -> Checkpoint.create(siteId, tableName, seq, rowCount));
    }
}
