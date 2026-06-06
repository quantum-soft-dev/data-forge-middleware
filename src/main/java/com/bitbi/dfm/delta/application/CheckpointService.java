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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ChangelogSegmentRepository segmentRepository;
    private final ChangelogSegmentService changelogSegmentService;
    private final CheckpointRepository checkpointRepository;
    private final DeltaSyncStateService syncStateService;
    private final S3CheckpointStorage checkpointStorage;
    private final SiteSchemaService siteSchemaService;

    public CheckpointService(ChangelogSegmentRepository segmentRepository,
                             ChangelogSegmentService changelogSegmentService,
                             CheckpointRepository checkpointRepository,
                             DeltaSyncStateService syncStateService,
                             S3CheckpointStorage checkpointStorage,
                             SiteSchemaService siteSchemaService) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.checkpointRepository = checkpointRepository;
        this.syncStateService = syncStateService;
        this.checkpointStorage = checkpointStorage;
        this.siteSchemaService = siteSchemaService;
    }

    /**
     * Build (or refresh) the checkpoint for a site by folding the latest checkpoint frame plus the
     * segments recorded since the checkpoint pointer.
     *
     * @param siteId site identifier
     * @return folded state: table → row-identity → folded row (empty if no segments)
     */
    @Transactional
    public Map<String, Map<String, FoldedRow>> buildCheckpoint(UUID siteId) {
        List<ChangelogSegment> segments = segmentRepository.findBySiteIdOrderByFirstSeq(siteId);
        if (segments.isEmpty()) {
            return Map.of();
        }

        long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
        boolean haveFrame = checkpointSeq > 0 && checkpointStorage.frameExists(siteId, checkpointSeq);

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

        List<ChangeRecord> newRecords = new ArrayList<>();
        for (ChangelogSegment segment : newSegments) {
            newRecords.addAll(changelogSegmentService.readRecords(segment.getS3Key()));
        }

        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(seed, newRecords);
        long seq = newSegments.get(newSegments.size() - 1).getLastSeq();

        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);

        state.forEach((tableName, rows) -> {
            Checkpoint checkpoint = findOrCreate(siteId, tableName, seq, rows.size());

            byte[] csv = CsvSnapshotWriter.toGzippedCsv(toDataRows(rows));
            checkpoint.attachCsv(checkpointStorage.uploadCsv(siteId, tableName, seq, csv));

            // Typed Parquet floor for Power BI — only for tables with a declared schema (CR §12).
            TableSchema tableSchema = schemas.get(tableName);
            if (tableSchema != null) {
                byte[] parquet = ParquetCheckpointWriter.toParquet(tableName, tableSchema, dataRows(rows));
                checkpoint.attachParquet(checkpointStorage.uploadParquet(siteId, tableName, seq, parquet));
            }

            checkpointRepository.save(checkpoint);
        });

        // Persist the new all-INSERT frame so the next build seeds from it and earlier segments can be pruned.
        checkpointStorage.uploadFrame(siteId, seq, ChangelogCodec.serialize(CheckpointFrame.toRecords(state)));
        syncStateService.recordCheckpoint(siteId, seq);
        return state;
    }

    private static Map<String, Map<String, Object>> toDataRows(Map<String, FoldedRow> rows) {
        Map<String, Map<String, Object>> dataRows = new LinkedHashMap<>();
        rows.forEach((identity, row) -> dataRows.put(identity, ValueMapper.toMap(row.data())));
        return dataRows;
    }

    private static List<Map<String, Value>> dataRows(Map<String, FoldedRow> rows) {
        return rows.values().stream().map(FoldedRow::data).toList();
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
