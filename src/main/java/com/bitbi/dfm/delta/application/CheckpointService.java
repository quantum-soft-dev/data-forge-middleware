package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds materialized checkpoints from the changelog (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Folds the site's changelog segments into current per-table state, records one
 * {@link Checkpoint} row per table (seq + row count), and advances the site's checkpoint pointer.
 * The folded state is returned so later stages can materialize CSV / Parquet snapshots.</p>
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

    public CheckpointService(ChangelogSegmentRepository segmentRepository,
                             ChangelogSegmentService changelogSegmentService,
                             CheckpointRepository checkpointRepository,
                             DeltaSyncStateService syncStateService,
                             S3CheckpointStorage checkpointStorage) {
        this.segmentRepository = segmentRepository;
        this.changelogSegmentService = changelogSegmentService;
        this.checkpointRepository = checkpointRepository;
        this.syncStateService = syncStateService;
        this.checkpointStorage = checkpointStorage;
    }

    /**
     * Build (or refresh) the checkpoint for a site by folding its changelog segments.
     *
     * @param siteId site identifier
     * @return folded state: table → row-identity → row(column → value) (empty if no segments)
     */
    @Transactional
    public Map<String, Map<String, Map<String, Object>>> buildCheckpoint(UUID siteId) {
        List<ChangelogSegment> segments = segmentRepository.findBySiteIdOrderByFirstSeq(siteId);
        if (segments.isEmpty()) {
            return Map.of();
        }

        List<ChangeRecord> all = new ArrayList<>();
        for (ChangelogSegment segment : segments) {
            all.addAll(changelogSegmentService.readRecords(segment.getS3Key()));
        }

        Map<String, Map<String, Map<String, Object>>> state = ChangelogFold.fold(Map.of(), all);
        long seq = segments.get(segments.size() - 1).getLastSeq();

        state.forEach((tableName, rows) -> {
            byte[] csv = CsvSnapshotWriter.toGzippedCsv(rows);
            String csvKey = checkpointStorage.uploadCsv(siteId, tableName, seq, csv);
            upsertCheckpoint(siteId, tableName, seq, rows.size(), csvKey);
        });
        syncStateService.recordCheckpoint(siteId, seq);
        return state;
    }

    private void upsertCheckpoint(UUID siteId, String tableName, long seq, long rowCount, String csvKey) {
        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(siteId, tableName)
                .map(existing -> {
                    existing.update(seq, rowCount);
                    return existing;
                })
                .orElseGet(() -> Checkpoint.create(siteId, tableName, seq, rowCount));
        checkpoint.attachCsv(csvKey);
        checkpointRepository.save(checkpoint);
    }
}
