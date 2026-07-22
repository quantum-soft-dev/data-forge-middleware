package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService.PresignedUrlResult;
import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService.PresignedDownload;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side application service for a batch's egressed delta Parquet files (feature 025).
 *
 * <p>A Delta v2 session commit persists one changelog segment per batch; the egress worker then
 * materializes one typed Parquet file per table with a declared schema
 * ({@code egress/{siteId}/{table}/delta/seq={first}-{last}.parquet} — see
 * {@link DeltaEgressService}). This service maps a (site, batch, table) triple back to that file
 * and issues a short-lived presigned download URL per click, mirroring
 * {@link DeltaCheckpointQueryService#presignDownload}.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSegmentParquetQueryService {

    private final ChangelogSegmentRepository segmentRepository;
    private final S3CheckpointStorage checkpointStorage;
    private final S3PresignedUrlService presignedUrlService;

    public DeltaSegmentParquetQueryService(ChangelogSegmentRepository segmentRepository,
                                           S3CheckpointStorage checkpointStorage,
                                           S3PresignedUrlService presignedUrlService) {
        this.segmentRepository = segmentRepository;
        this.checkpointStorage = checkpointStorage;
        this.presignedUrlService = presignedUrlService;
    }

    /**
     * Issue a fresh presigned download URL (15 min) for one table's delta Parquet file of a batch.
     *
     * @param siteId    site identifier (segments of other sites are ignored)
     * @param batchId   batch (= Delta session) identifier
     * @param tableName table whose delta file to download
     * @return presigned download, or empty when the batch has no segment or the file was never
     *         egressed (e.g. the table has no declared schema)
     */
    // No @Transactional: the only DB read (findByBatchId) runs in the repository's own
    // transaction and is materialized before the S3 HEAD/presign round-trips — holding a
    // HikariCP connection across network calls would starve the pool under S3 latency.
    public Optional<PresignedDownload> presignBatchTableParquet(UUID siteId, UUID batchId, String tableName) {
        List<ChangelogSegment> segments = segmentRepository.findByBatchId(batchId).stream()
                .filter(segment -> siteId.equals(segment.getSiteId()))
                .toList();

        for (ChangelogSegment segment : segments) {
            long firstSeq = segment.getFirstSeq();
            long lastSeq = segment.getLastSeq();
            if (!checkpointStorage.deltaExists(siteId, tableName, firstSeq, lastSeq)) {
                continue;
            }
            String s3Key = S3CheckpointStorage.deltaKey(siteId, tableName, firstSeq, lastSeq);
            String fileName = "%s_seq%d-%d.parquet".formatted(tableName, firstSeq, lastSeq);
            PresignedUrlResult result = presignedUrlService.generatePresignedUrl(s3Key, fileName);
            return Optional.of(new PresignedDownload(result.url(), fileName, result.expiresAt()));
        }
        return Optional.empty();
    }
}
