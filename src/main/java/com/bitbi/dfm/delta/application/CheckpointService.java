package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds materialized checkpoints from the changelog (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Reconstruction is <b>incremental</b>: it seeds from the latest all-INSERT checkpoint frame@M and
 * folds only the segments with {@code first_seq > M}, then materializes a CSV snapshot per table,
 * records one {@link Checkpoint} row per table, persists a new frame@now, and advances the site's
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

    public CheckpointService(ChangelogSegmentRepository segmentRepository,
                             ChangelogSegmentService changelogSegmentService,
                             CheckpointRepository checkpointRepository,
                             DeltaSyncStateService syncStateService,
                             S3CheckpointStorage checkpointStorage,
                             SiteSchemaService siteSchemaService,
                             DeltaMetrics metrics) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.checkpointRepository = checkpointRepository;
        this.syncStateService = syncStateService;
        this.checkpointStorage = checkpointStorage;
        this.siteSchemaService = siteSchemaService;
        this.metrics = metrics;
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

        // Seed from the durable checkpoint frame when present; otherwise fold the full history.
        Map<String, Map<String, FoldedRow>> seed = haveFrame
                ? ChangelogFold.fold(Map.of(), ChangelogCodec.parse(checkpointStorage.downloadFrame(siteId, checkpointSeq)))
                : Map.of();
        long foldFrom = haveFrame ? checkpointSeq : 0L;

        List<ChangelogSegment> newSegments = segments.stream()
                .filter(segment -> segment.getFirstSeq() > foldFrom)
                .toList();
        if (newSegments.isEmpty()) {
            return seed; // nothing recorded since the last checkpoint
        }

        // Time only real materialization work (T5.3).
        return metrics.timeCheckpoint(() -> materialize(siteId, seed, newSegments));
    }

    private Map<String, Map<String, FoldedRow>> materialize(UUID siteId,
                                                            Map<String, Map<String, FoldedRow>> seed,
                                                            List<ChangelogSegment> newSegments) {
        List<ChangeRecord> newRecords = new ArrayList<>();
        for (ChangelogSegment segment : newSegments) {
            newRecords.addAll(changelogSegmentService.readRecords(segment.getS3Key()));
        }

        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(seed, newRecords);
        long seq = newSegments.get(newSegments.size() - 1).getLastSeq();

        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);

        // Per-segment delta Parquet is event-driven (Task 8, DeltaEgressService); the checkpoint
        // additionally materializes the full per-table load: typed Parquet (target format, B3)
        // for tables with a declared schema, plus the legacy CSV (Bit BI, §11) and the frame seed.
        state.forEach((tableName, rows) -> {
            Checkpoint checkpoint = findOrCreate(siteId, tableName, seq, rows.size());

            byte[] csv = CsvSnapshotWriter.toGzippedCsv(toDataRows(rows));
            checkpoint.attachCsv(checkpointStorage.uploadCsv(siteId, tableName, seq, csv));

            TableSchema tableSchema = schemas.get(tableName);
            if (tableSchema != null) {
                // One table's coercion failure (schema drift, bad value) must not abort the whole
                // build: the pointer would freeze, retention would stop, and segments would grow
                // unbounded. Skip its Parquet (CSV above still ships) and keep going — the same
                // skip-and-continue contract as DeltaEgressService.
                try {
                    byte[] parquet = ParquetCheckpointWriter.toParquet(tableName, tableSchema, dataRows(rows));
                    checkpoint.attachParquet(checkpointStorage.uploadParquet(siteId, tableName, seq, parquet));
                } catch (RuntimeException e) {
                    log.warn("Checkpoint Parquet failed for table {} of site {} — skipping Parquet, "
                            + "CSV still written (check the declared schema against the data)",
                            tableName, siteId, e);
                }
            }

            checkpointRepository.save(checkpoint);
        });

        // Persist the new all-INSERT frame so the next build seeds from it and earlier segments can be pruned.
        checkpointStorage.uploadFrame(siteId, seq, ChangelogCodec.serialize(CheckpointFrame.toRecords(state)));
        syncStateService.recordCheckpoint(siteId, seq);
        return state;
    }

    private static List<Map<String, Value>> dataRows(Map<String, FoldedRow> rows) {
        return rows.values().stream().map(FoldedRow::data).toList();
    }

    private static Map<String, Map<String, Object>> toDataRows(Map<String, FoldedRow> rows) {
        Map<String, Map<String, Object>> dataRows = new LinkedHashMap<>();
        rows.forEach((identity, row) -> dataRows.put(identity, ValueMapper.toMap(row.data())));
        return dataRows;
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
