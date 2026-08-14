package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds materialized checkpoints from the changelog (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Reconstruction is <b>incremental</b>: it seeds from the latest all-INSERT checkpoint frame@M and
 * folds only the segments with {@code first_seq > M}, then materializes a Parquet snapshot per
 * table, records one {@link Checkpoint} row per table, persists a new frame@now, and advances the site's
 * checkpoint pointer. Because the frame is a self-contained seed, segments at or below the checkpoint
 * can be pruned (T3.5b) without breaking the next build.</p>
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
    private final Path tempDirectory;
    private final long maxTempBytes;

    public CheckpointService(ChangelogSegmentRepository segmentRepository,
                             ChangelogSegmentService changelogSegmentService,
                             CheckpointRepository checkpointRepository,
                             DeltaSyncStateService syncStateService,
                             S3CheckpointStorage checkpointStorage,
                             SiteSchemaService siteSchemaService,
                             DeltaMetrics metrics,
                             DeltaParquetProperties parquetProperties,
                             ApplicationEventPublisher eventPublisher,
                             // fully qualified: the delta wire Value is imported above
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.temp-dir:${java.io.tmpdir}}") String tempDirectory,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${delta.checkpoint.max-temp-bytes:10737418240}") long maxTempBytes) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.checkpointRepository = checkpointRepository;
        this.syncStateService = syncStateService;
        this.checkpointStorage = checkpointStorage;
        this.siteSchemaService = siteSchemaService;
        this.metrics = metrics;
        this.parquetProperties = parquetProperties;
        this.eventPublisher = eventPublisher;
        this.tempDirectory = Path.of(tempDirectory);
        this.maxTempBytes = maxTempBytes;
    }

    /**
     * Build (or refresh) the checkpoint for a site by folding the latest checkpoint frame plus the
     * segments recorded since the checkpoint pointer.
     *
     * <p>No {@code @Transactional}: the build spans frame + per-segment S3 downloads and per-table
     * S3 uploads — holding a HikariCP connection across those network calls would pin it for the
     * whole build (the pattern removed from the read path in 025-T3). Repository calls run in their
     * own short transactions; a failure mid-loop leaves idempotent per-table rows that the next
     * build overwrites, and the pointer only advances at the very end ({@code recordCheckpoint}).</p>
     *
     * @param siteId site identifier
     * @return folded state: table → row-identity → folded row (empty if no segments)
     */
    public Map<String, Map<String, FoldedRow>> buildCheckpoint(UUID siteId) {
        List<ChangelogSegment> segments = segmentRepository.findBySiteIdOrderByFirstSeq(siteId);
        if (segments.isEmpty()) {
            return Map.of();
        }

        long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
        boolean haveFrame = checkpointSeq > 0 && checkpointStorage.frameExists(siteId, checkpointSeq);

        // A frame@checkpointSeq must exist once the pointer advanced (uploadFrame precedes
        // recordCheckpoint). If it reads as absent — deleted, or an S3 HEAD denial masquerading
        // as absence — a refold is lossless only while the full history survives; after retention
        // pruning it would silently publish a truncated checkpoint and advance the pointer, making
        // the loss durable. Refuse and let the build fail loudly instead.
        if (checkpointSeq > 0 && !haveFrame && segments.get(0).getFirstSeq() > 1) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Checkpoint frame@" + checkpointSeq + " for site " + siteId
                            + " is unreadable and earlier segments are pruned — refusing lossy refold", null);
        }

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
                return seed;
            }
            return materialize(siteId, seed, newSegments);
        });
    }

    private Map<String, Map<String, FoldedRow>> materialize(UUID siteId,
                                                            Map<String, Map<String, FoldedRow>> seed,
                                                            List<ChangelogSegment> newSegments) {
        Map<String, Map<String, FoldedRow>> state = metrics.timeCheckpointPhase("fold", () -> {
            List<ChangeRecord> newRecords = new ArrayList<>();
            for (ChangelogSegment segment : newSegments) {
                newRecords.addAll(changelogSegmentService.readRecords(segment.getS3Key()));
            }
            return ChangelogFold.fold(seed, newRecords);
        });
        long seq = newSegments.get(newSegments.size() - 1).getLastSeq();

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
            Checkpoint checkpoint = findOrCreate(siteId, tableName, seq, rows.size());

            TableSchema tableSchema = schemas.get(tableName);
            if (tableSchema == null) {
                // Parquet needs the declared schema, and there is no CSV left to fall back on: this
                // table simply has nothing to download until a schema arrives. The client is
                // required to SubmitSchema before its first session, so this means the site is
                // misconfigured — count it so the hole is visible rather than silent.
                checkpoint.detachParquet();
                metrics.checkpointTableUnmaterialized("no_schema");
                log.warn("No declared schema for table {} of site {} — checkpoint row recorded "
                        + "without a downloadable artifact (the client must SubmitSchema)",
                        tableName, siteId);
            } else {
                // One table at a time: write this table's rows to disk, hand the file to S3, drop
                // it. Materialization therefore costs one row-group buffer and one scratch file at
                // a time instead of one encoded Parquet per table. (The frame upload below still
                // builds an all-tables copy — issue #126.)
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
                } catch (RuntimeException e) {
                    // The row's seq and rowCount advance regardless (the fold succeeded), so the
                    // previous build's key would now sit beside a newer seq and be served as its
                    // snapshot. Detach it: an absent file is honest, stale rows under a fresh seq
                    // are not — and with the CSV gone nothing else masks the gap.
                    checkpoint.detachParquet();
                    metrics.checkpointTableUnmaterialized("parquet_failed");
                    log.warn("Checkpoint Parquet failed for table {} of site {} — the table has no "
                            + "artifact this build (check the declared schema against the data, or "
                            + "delta.checkpoint.max-temp-bytes against the table's size)",
                            tableName, siteId, e);
                } finally {
                    // The scratch file is this build's litter whichever way the table ended: kept,
                    // it would fill the node one checkpoint cycle at a time.
                    deleteQuietly(snapshot, tableName, siteId);
                }
            }

            checkpointRepository.save(checkpoint);
        });

        // Persist the new all-INSERT frame so the next build seeds from it and earlier segments can be pruned.
        metrics.timeCheckpointPhase("upload", () ->
                checkpointStorage.uploadFrame(siteId, seq,
                        ChangelogCodec.serialize(CheckpointFrame.toRecords(state))));
        syncStateService.recordCheckpoint(siteId, seq);
        // The single choke point every checkpoint build passes through, scheduled or forced. The
        // Bit BI auto-reinit after a history wipe (issue #89) hangs off it, because this is the
        // first moment post-wipe at which there are checkpoint seqs to freeze as SQL baselines.
        // The checkpoint is already durable by now, so a listener's failure must not be allowed to
        // fail the build behind it — that would freeze the pointer and stop retention.
        try {
            eventPublisher.publishEvent(new CheckpointRecordedEvent(siteId, seq));
        } catch (RuntimeException e) {
            log.error("A checkpoint listener failed for site {} at seq {}; the checkpoint itself "
                    + "is committed", siteId, seq, e);
        }
        return state;
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
     * hit every table of every site alike. Skipping per table would detach every snapshot key while
     * {@code recordCheckpoint} still advanced the pointer, and nothing could put them back: the
     * next build sees no new segments and returns early, so even a forced rebuild is a no-op.
     * Fail the build instead, leaving the pointer where it was so the next run redoes everything;
     * {@code CheckpointScheduler} catches per site, so one site's failure does not stop the sweep.
     *
     * <p>A failure <em>during</em> the write stays a per-table skip (the general catch above), so a
     * single oversized or unrenderable table cannot freeze the pointer and stop retention.</p>
     */
    private Path createScratchFile(UUID siteId) {
        try {
            return Files.createTempFile(tempDirectory, "checkpoint-" + siteId + "-", ".parquet");
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
