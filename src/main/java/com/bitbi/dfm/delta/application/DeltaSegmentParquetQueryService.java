package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService.PresignedUrlResult;
import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService.PresignedDownload;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-side application service for a batch's unified per-table Parquet files (036, issue #93).
 *
 * <p>The exact {@code (site, batch, table)} manifest row is authoritative. Segment-level realtime
 * egress remains a separate compatibility contract and is never scanned by this download path.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSegmentParquetQueryService {

    private final BatchParquetArtifactRepository artifactRepository;
    private final S3PresignedUrlService presignedUrlService;

    public DeltaSegmentParquetQueryService(BatchParquetArtifactRepository artifactRepository,
                                           S3PresignedUrlService presignedUrlService) {
        this.artifactRepository = artifactRepository;
        this.presignedUrlService = presignedUrlService;
    }

    /**
     * Issue a fresh presigned download URL (15 min) for one table's unified batch Parquet.
     *
     * @param siteId    site identifier (segments of other sites are ignored)
     * @param batchId   batch (= Delta session) identifier
     * @param tableName table whose delta file to download
     * @return presigned download, or empty when no manifest exists / finalization failed
     */
    // No @Transactional: the only DB read (findByBatchId) runs in the repository's own
    // transaction and is materialized before the S3 HEAD/presign round-trips — holding a
    // HikariCP connection across network calls would starve the pool under S3 latency.
    public Optional<PresignedDownload> presignBatchTableParquet(UUID siteId, UUID batchId, String tableName) {
        Optional<BatchParquetArtifact> found = artifactRepository
                .findBySiteIdAndBatchIdAndTableName(siteId, batchId, tableName);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BatchParquetArtifact artifact = found.get();
        if (artifact.getStatus() == BatchParquetArtifactStatus.PENDING
                || artifact.getStatus() == BatchParquetArtifactStatus.BUILDING) {
            throw new BatchParquetNotReadyException(batchId, tableName);
        }
        if (artifact.getStatus() != BatchParquetArtifactStatus.READY || artifact.getS3Key() == null) {
            return Optional.empty();
        }
        String safeTable = tableName.replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = "%s_batch-%s.parquet".formatted(safeTable, batchId);
        PresignedUrlResult result = presignedUrlService.generatePresignedUrl(artifact.getS3Key(), fileName);
        return Optional.of(new PresignedDownload(result.url(), fileName, result.expiresAt()));
    }

    /** A manifest exists, but S3 publication has not completed yet. */
    public static class BatchParquetNotReadyException extends RuntimeException {
        public BatchParquetNotReadyException(UUID batchId, String tableName) {
            super("Unified Parquet finalization is still in progress for batch " + batchId
                    + ", table " + tableName);
        }
    }
}
