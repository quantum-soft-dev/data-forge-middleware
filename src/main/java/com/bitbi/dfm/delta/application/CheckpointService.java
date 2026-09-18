package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.SegmentSeqRange;
import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.domain.events.CheckpointRecordedEvent;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.ObjectPresence;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.application.SiteSchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * <p><b>This class chooses the path and owns the order; it does not do the work</b> (issue #297).
 * Which frame producer runs — the fold, the streamed bootstrap or the merge — and whether the visit
 * is idle, refused or discarded is decided here, and so is the sequence every advancing build
 * follows: the epoch checked with nothing in the bucket (#136/#142), the frame uploaded before any
 * snapshot (#153), the pointer and its event last. Producing the frame is
 * {@link CheckpointFrameProducer}; writing the snapshots and keeping the {@code checkpoints} rows is
 * {@link CheckpointSnapshotMaterializer}.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final DeltaSyncStateService syncStateService;
    private final S3CheckpointStorage checkpointStorage;
    private final DeltaMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final CheckpointEpochGuard epochGuard;
    private final CheckpointRetryProperties retryProperties;
    private final CheckpointShutdownCheck shutdown;
    /** One fold at a time in this JVM, so {@code delta.checkpoint.max-fold-bytes} bounds the process (issue #178). */
    private final CheckpointFoldBudget foldBudget;
    /**
     * The bound on the scratch <em>directory</em> this build shares with the completed-batch
     * workers (issue #150), and the directory itself.
     */
    private final CheckpointScratch scratch;
    /** Produces the build's reload frame — by fold, by streamed bootstrap or by merge (issue #297). */
    private final CheckpointFrameProducer frames;
    /** Materializes the per-table snapshots and keeps the {@code checkpoints} rows (issue #297). */
    private final CheckpointSnapshotMaterializer snapshots;
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
                             // fully qualified: the delta wire Value shares the simple name
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
        this.syncStateService = syncStateService;
        this.checkpointStorage = checkpointStorage;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.epochGuard = epochGuard;
        this.retryProperties = retryProperties;
        this.shutdown = new CheckpointShutdownCheck(shutdownSignal);
        this.foldBudget = foldBudget;
        this.scratch = new CheckpointScratch(Path.of(tempDirectory), scratchBudget);
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
        // The three roles this service used to hold in one class (issue #297). Built here rather than
        // injected so the bean, its configuration keys and its constructor stay exactly what they
        // were: the path selection below is still the only entry point, and the order it calls
        // these in is what #136/#142/#153 rest on.
        this.frames = new CheckpointFrameProducer(changelogSegmentService, checkpointStorage, metrics,
                scratch, shutdown, maxFrameTempBytes, resolveMaxFoldBytes(maxFoldBytes), maxMergePartitions);
        this.snapshots = new CheckpointSnapshotMaterializer(checkpointRepository, checkpointStorage,
                siteSchemaService, metrics, parquetProperties, epochGuard, retryProperties, scratch,
                shutdown, maxTempBytes, snapshotWriters);
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
            shutdown.stopIfShuttingDown(siteId);
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
            snapshots.settleSiteWide(siteId, epoch, pass);
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
            if (pass != SnapshotPass.FORCE && !snapshots.hasRetryableUnmaterializedTables(siteId)) {
                log.debug("Frame@{} for site {} is gone and only below-checkpoint segments remain; "
                        + "the retry has already been spent — ending the visit quietly", checkpointSeq, siteId);
                return Map.of();
            }
            metrics.checkpointBuildAborted("lossy_refold");
            snapshots.settleSiteWide(siteId, epoch, pass);
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
                            && !snapshots.hasRetryableUnmaterializedTables(siteId)))) {
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
                    frames.foldSite(siteId, checkpointSeq, haveFrame, newSegments);

            if (newSegments.isEmpty()) {
                snapshots.writeSnapshots(siteId, state, checkpointSeq, idlePass, epoch);
                return state;
            }
            return materialize(siteId, state, newSegments, epoch);
        });
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
     * <p>{@link CheckpointFrameProducer#foldSite} folds the frame and then the segments into one map of every surviving
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

        scratch.prepareDirectory();
        Path frame = scratch.createFile(siteId, ".pb.gz");
        // One lease per attempt, and the successful attempt's is held for the whole build: the
        // snapshots are written by re-reading this file, so its bytes are on the volume until the
        // last table is done. A partitioned retry rewrites the file from scratch, and its lease has
        // to go with the bytes it charged for — CappedOutputStream charges as it writes and a lease
        // gives everything back only on close.
        ScratchLease[] lease = {null};
        try {
            CheckpointFrameWriter.FrameManifest manifest =
                    frames.mergeIntoFrame(siteId, checkpointSeq, seq, newSegments, frame, lease);

            // The same order the other two paths take, and for the same reasons (issue #153):
            // notice a closing process before the longest single call of the build, and check the
            // epoch with nothing uploaded so a wipe that has already committed is seen before the
            // object is in the bucket rather than after.
            shutdown.stopIfShuttingDown(siteId);
            epochGuard.requireEpoch(siteId, epoch);
            if (advancing) {
                frames.uploadWrittenFrame(siteId, seq, frame);
            }

            int passes = snapshots.writeSnapshotsFromFrame(siteId, frame, manifest, seq, epoch, pass);
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
            CheckpointScratch.deleteQuietly(frame, "_frame", siteId);
            if (lease[0] != null) {
                lease[0].close();
            }
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
        scratch.prepareDirectory();
        Path frame = scratch.createFile(siteId, ".pb.gz");
        // Held for the whole build, unlike the general path's frame lease: the snapshots are written
        // by re-reading this file, so its bytes are on the volume until the last table is done. The
        // checkpoint reserve of issue #193 is what keeps a completed-batch backlog out of them.
        ScratchLease lease = scratch.budget().open(ParquetScratchBudget.CHECKPOINT_FRAME);
        try {
            CheckpointFrameWriter.FrameManifest manifest =
                    frames.streamSnapshotIntoFrame(siteId, segments, frame, lease);
            if (manifest == null) {
                return null;
            }

            // The same order the general path takes, and for the same reasons (issue #153): notice
            // a closing process before the longest single call of the build, and check the epoch
            // with nothing written so a wipe that has already committed is seen before the object
            // is in the bucket rather than after.
            shutdown.stopIfShuttingDown(siteId);
            epochGuard.requireEpoch(siteId, epoch);
            frames.uploadWrittenFrame(siteId, seq, frame);

            int passes = snapshots.writeSnapshotsFromFrame(siteId, frame, manifest, seq, epoch,
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
            CheckpointScratch.deleteQuietly(frame, "_frame", siteId);
            lease.close();
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
        shutdown.stopIfShuttingDown(siteId);
        epochGuard.requireEpoch(siteId, epoch);
        frames.uploadFoldedFrame(siteId, seq, state);
        snapshots.writeSnapshots(siteId, state, seq, SnapshotPass.INCREMENTAL, epoch);

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
     * <p>Not public because it must not be caught anywhere but in {@link #run}: every other handler
     * of a checkpoint build exists to turn a failure into a durable conclusion, which is precisely
     * what this one must not become. Package-private only so the frame producer and the snapshot
     * materializer can raise it (issue #297); they rethrow it untouched.</p>
     */
    static final class BuildEndedByShutdownException extends RuntimeException {

        BuildEndedByShutdownException(UUID siteId, String tableName, Throwable cause) {
            super("The checkpoint build for site " + siteId
                    + (tableName == null ? "" : " (table " + tableName + ")")
                    + " ended because the application is shutting down", cause);
        }
    }
}
