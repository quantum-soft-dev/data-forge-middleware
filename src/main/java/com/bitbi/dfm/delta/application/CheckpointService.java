package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.domain.events.CheckpointRecordedEvent;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds materialized checkpoints from the changelog (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Reconstruction is <b>incremental</b>: it seeds from the latest all-INSERT checkpoint frame@M and
 * folds only the segments with {@code first_seq > M}, then materializes a Parquet snapshot per
 * table, records one {@link Checkpoint} row per table, persists a new frame@now, and advances the site's
 * checkpoint pointer. Because the frame is a self-contained seed, segments at or below the checkpoint
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
    private final Path tempDirectory;
    /** Per-table snapshot ceiling: crossing it skips that table (issue #138). */
    private final long maxTempBytes;
    /** Reload-frame ceiling: crossing it aborts the build, so it is deliberately its own key. */
    private final long maxFrameTempBytes;

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
                             // fully qualified: the delta wire Value is imported above
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.temp-dir:${java.io.tmpdir}}") String tempDirectory,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-temp-bytes:10737418240}") long maxTempBytes,
                             // Falls back to the per-table key, not to a literal: before #138 one
                             // key governed both files, so an operator who had lowered it to fit a
                             // small scratch disk must keep the frame bounded by that same number
                             // until they say otherwise.
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-frame-temp-bytes:"
                                             + "${delta.checkpoint.max-temp-bytes:10737418240}}")
                             long maxFrameTempBytes) {
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
        this.tempDirectory = Path.of(tempDirectory);
        this.maxTempBytes = maxTempBytes;
        this.maxFrameTempBytes = maxFrameTempBytes;
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
        return run(siteId, SnapshotPass.RETRY_MISSING);
    }

    /**
     * Same fold as {@link #buildCheckpoint(UUID)}, but an idle site rewrites every table from the
     * frame. New segments still take the incremental path and advance the pointer.
     *
     * @param siteId site identifier
     * @return folded state: table → row-identity → folded row (empty if there is nothing to fold)
     */
    public Map<String, Map<String, FoldedRow>> rebuildFromFrame(UUID siteId) {
        return run(siteId, SnapshotPass.FORCE);
    }

    private Map<String, Map<String, FoldedRow>> run(UUID siteId, SnapshotPass idlePass) {
        // The epoch is read *before* the segments, and the order is load-bearing. Read the other way
        // round, a re-baseline (or a wipe) committing between the two would hand the build the
        // pre-reset segment list together with the new epoch: every guarded write would then compare
        // equal and be approved, and the build would fold the discarded baseline, upload a frame at
        // its last seq and move the pointer there — the resurrection the guard exists to stop,
        // arrived at through the guard. This way the epoch can only be older-or-equal to the data it
        // guards, which is the direction the guard refuses.
        DeltaSyncStateService.SyncStateView syncState = syncStateService.getSyncState(siteId);
        List<ChangelogSegment> segments = segmentRepository.findBySiteIdOrderByFirstSeq(siteId);
        long checkpointSeq = syncState.lastCheckpointSeq();
        // The epoch this build belongs to. Every row it writes is checked against it under the
        // site_sync_state row lock, so a history wipe (issue #136) or a re-baseline (issue #142)
        // that commits mid-build discards the build instead of having its deletes undone by it.
        SiteEpoch epoch = syncState.epoch();
        boolean haveFrame = checkpointSeq > 0 && checkpointStorage.frameExists(siteId, checkpointSeq);
        boolean historyPruned = segments.isEmpty() || segments.get(0).getFirstSeq() > 1;

        // A frame@checkpointSeq must exist once the pointer advanced (uploadFrame precedes
        // recordCheckpoint). If it reads as absent — deleted, or an S3 HEAD denial masquerading
        // as absence — a refold is lossless only while the full history survives; after retention
        // pruning it would silently publish a truncated checkpoint and advance the pointer, making
        // the loss durable. Refuse and let the build fail loudly instead.
        if (checkpointSeq > 0 && !haveFrame && historyPruned) {
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
                return Map.of();
            }
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Checkpoint frame@" + checkpointSeq + " for site " + siteId
                            + " is unreadable and earlier segments are pruned — refusing lossy refold",
                    null);
        }
        if (segments.isEmpty() && !haveFrame) {
            return Map.of();
        }

        try {
            return build(siteId, idlePass, segments, checkpointSeq, epoch, haveFrame);
        } catch (CheckpointEpochGuard.EpochChangedException e) {
            // Not a failure of this build: the site's baseline was replaced under it, so there is
            // nothing left to publish. Whatever rows it did commit were taken by the wipe's (or the
            // re-baseline's) own deletes — they can only have committed before the row lock was
            // taken — and the objects it uploaded are orphans the next wipe sweeps.
            log.warn("Discarding the checkpoint build for site {}: {}", siteId, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Map<String, FoldedRow>> build(UUID siteId,
                                                      SnapshotPass idlePass,
                                                      List<ChangelogSegment> segments,
                                                      long checkpointSeq,
                                                      SiteEpoch epoch,
                                                      boolean haveFrame) {
        // Empty incremental work still belongs in phase=total: the frame download already ran.
        return metrics.timeCheckpoint(() -> {
            byte[] frameBytes = haveFrame
                    ? metrics.timeCheckpointPhase("download_frame",
                            () -> checkpointStorage.downloadFrame(siteId, checkpointSeq))
                    : null;
            Map<String, Map<String, FoldedRow>> seed = frameBytes == null
                    ? Map.of()
                    : ChangelogFold.fold(Map.of(), ChangelogCodec.parse(frameBytes));
            long foldFrom = haveFrame ? checkpointSeq : 0L;

            List<ChangelogSegment> newSegments = segments.stream()
                    .filter(segment -> segment.getFirstSeq() > foldFrom)
                    .toList();
            if (newSegments.isEmpty()) {
                if (!haveFrame || (idlePass == SnapshotPass.RETRY_MISSING
                        && !hasUnmaterializedTables(siteId))) {
                    return seed;
                }
                writeSnapshots(siteId, seed, checkpointSeq, idlePass, epoch);
                return seed;
            }
            return materialize(siteId, seed, newSegments, epoch);
        });
    }

    private Map<String, Map<String, FoldedRow>> materialize(UUID siteId,
                                                            Map<String, Map<String, FoldedRow>> seed,
                                                            List<ChangelogSegment> newSegments,
                                                            SiteEpoch epoch) {
        Map<String, Map<String, FoldedRow>> state = metrics.timeCheckpointPhase("fold", () -> {
            List<ChangeRecord> newRecords = new ArrayList<>();
            for (ChangelogSegment segment : newSegments) {
                newRecords.addAll(changelogSegmentService.readRecords(segment.getS3Key()));
            }
            return ChangelogFold.fold(seed, newRecords);
        });
        long seq = newSegments.get(newSegments.size() - 1).getLastSeq();
        writeSnapshots(siteId, state, seq, SnapshotPass.INCREMENTAL, epoch);

        // Persist the new all-INSERT frame so the next build seeds from it and earlier segments
        // can be pruned. Same file-backed path as the snapshot (issue #126): one record at a
        // time into a scratch file, then RequestBody.fromFile — never a collected List and
        // never a gzip byte[]. The site fold itself stays in heap.
        //
        // Its own ceiling, not the snapshot's (issue #138). The two files share a directory but
        // not a failure mode: an oversized table is skipped and repaired by the next build, while
        // an oversized frame ends the build, because the frame is the next incremental seed. One
        // key for both meant the value had to be set for the harsher of the two, which left it
        // above the deployed scratch volume and made a kubelet eviction the first thing to happen.
        Path frame = createScratchFile(siteId, ".pb.gz");
        try {
            metrics.timeCheckpointPhase("upload", () -> {
                try (OutputStream out = new CappedOutputStream(
                        Files.newOutputStream(frame), maxFrameTempBytes)) {
                    ChangelogCodec.write(CheckpointFrame.records(state), out);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
                }
                checkpointStorage.uploadFrame(siteId, seq, frame);
            });
        } catch (ArtifactSizeLimitExceededException e) {
            // Both ceilings raise the same exception with the same "temp-file limit of N bytes"
            // text, and the per-table one is reported by its own counter — say which guard this
            // was and name the key, or the operator has only a byte count to go on. Rethrown
            // unchanged: an oversized frame still ends the build.
            log.error("The checkpoint reload frame for site {} at seq {} crossed "
                    + "delta.checkpoint.max-frame-temp-bytes ({} bytes) — the build is abandoned. "
                    + "last_checkpoint_seq stays where it was, so retention is frozen and the next "
                    + "build repeats this, but the per-table snapshots of this build were already "
                    + "written at seq {} and their predecessors are now unreferenced objects. "
                    + "Raise that key (and the scratch volume behind it) rather than the per-table "
                    + "ceiling",
                    siteId, seq, maxFrameTempBytes, seq);
            throw e;
        } finally {
            deleteQuietly(frame, "_frame", siteId);
        }
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
        try {
            eventPublisher.publishEvent(new CheckpointRecordedEvent(siteId, seq, epoch));
        } catch (RuntimeException e) {
            log.error("A checkpoint listener failed for site {} at seq {}; the checkpoint itself "
                    + "is committed", siteId, seq, e);
        }
        return state;
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
        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);

        // Every table's snapshot goes through the same scratch directory, one at a time. Creating
        // it is systemic, not per-table: if it fails, no table can be materialized this build, so
        // let it fail the build loudly instead of counting every table as its own skip.
        try {
            Files.createDirectories(tempDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot prepare the checkpoint scratch directory " + tempDirectory, e);
        }

        // Per-segment delta Parquet is event-driven (Task 8, DeltaEgressService); the checkpoint
        // additionally materializes the full per-table load as typed Parquet (the only format V2
        // produces since issue #113) plus the frame seed.
        state.forEach((tableName, rows) -> {
            if (pass == SnapshotPass.RETRY_MISSING) {
                Optional<Checkpoint> existing =
                        checkpointRepository.findBySiteIdAndTableName(siteId, tableName);
                if (existing.isPresent() && existing.get().getS3KeyParquet() != null) {
                    return;
                }
            }
            Checkpoint checkpoint = findOrCreate(siteId, tableName, seq, rows.size());

            TableSchema tableSchema = schemas.get(tableName);
            if (tableSchema == null) {
                // Parquet needs the declared schema, and there is no CSV left to fall back on: this
                // table simply has nothing to download until a schema arrives. The client is
                // required to SubmitSchema before its first session, so this means the site is
                // misconfigured — count it so the hole is visible rather than silent.
                // Write first, then report — the reverse of the catch below, and deliberately so:
                // there is no cause to preserve here, and an epoch refusal must leave the meter
                // alone. A discarded build has no tables to report a hole for.
                if (abandonStaleSnapshot(checkpoint, pass)) {
                    epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
                }
                metrics.checkpointTableUnmaterialized("no_schema");
                log.warn("No declared schema for table {} of site {} — checkpoint row recorded "
                        + "without a downloadable artifact (the client must SubmitSchema)",
                        tableName, siteId);
                return;
            }

            // One table at a time: write this table's rows to disk, hand the file to S3, drop
            // it. Materialization therefore costs one row-group buffer and one scratch file at
            // a time instead of one encoded Parquet per table. The new frame (issue #126) is
            // serialized later, in materialize, and only when seq advanced.
            //
            // One table's coercion failure (schema drift, bad value) must not abort the whole
            // build: the pointer would freeze, retention would stop, and segments would grow
            // unbounded. Skip that table and keep going — the same skip-and-continue contract
            // as DeltaEgressService.
            Path snapshot = createScratchFile(siteId);
            try {
                metrics.timeCheckpointPhase("parquet", () ->
                        ParquetCheckpointWriter.writeParquet(snapshot, tableName, tableSchema,
                                dataRows(rows), maxTempBytes, parquetProperties.rowGroupBytes()));
                metrics.timeCheckpointPhase("upload", () ->
                        checkpoint.attachParquet(checkpointStorage.uploadParquet(
                                siteId, tableName, seq, snapshot)));
                epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
            } catch (CheckpointEpochGuard.EpochChangedException e) {
                // A replaced baseline is not a fact about this table: nothing this build produced
                // may be published, so it must escape the per-table skip below and end the build.
                throw e;
            } catch (RuntimeException e) {
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
                    epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
                }
            } finally {
                // The scratch file is this build's litter whichever way the table ended: kept,
                // it would fill the node one checkpoint cycle at a time.
                deleteQuietly(snapshot, tableName, siteId);
            }
        });
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

    private boolean hasUnmaterializedTables(UUID siteId) {
        return checkpointRepository.findBySiteId(siteId).stream()
                .anyMatch(checkpoint -> checkpoint.getS3KeyParquet() == null);
    }

    /**
     * A lazily iterated view of one table's folded rows — the writer traverses it (twice at most,
     * for the decimal envelope) instead of receiving a materialized copy of the state.
     */
    private static Iterable<Map<String, Value>> dataRows(Map<String, FoldedRow> rows) {
        return () -> rows.values().stream().map(FoldedRow::data).iterator();
    }

    /**
     * Create this table's scratch file. A failure here says the scratch directory itself is
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
