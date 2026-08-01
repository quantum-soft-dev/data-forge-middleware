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
 * <p>A batch that closed before 036 shipped has no manifest row, so the first download request
 * enqueues one from its raw segments and answers {@code 409}; the artifact is built in the
 * background and the next click downloads it.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class BatchParquetDownloadService {

    private final BatchParquetArtifactRepository artifactRepository;
    private final BatchParquetFinalizationService finalizationService;
    private final BatchParquetFinalizationWorker finalizationWorker;
    private final S3PresignedUrlService presignedUrlService;

    public BatchParquetDownloadService(BatchParquetArtifactRepository artifactRepository,
                                           BatchParquetFinalizationService finalizationService,
                                           BatchParquetFinalizationWorker finalizationWorker,
                                           S3PresignedUrlService presignedUrlService) {
        this.artifactRepository = artifactRepository;
        this.finalizationService = finalizationService;
        this.finalizationWorker = finalizationWorker;
        this.presignedUrlService = presignedUrlService;
    }

    /**
     * Issue a fresh presigned download URL (15 min) for one table's unified batch Parquet.
     *
     * @param siteId    site identifier (segments of other sites are ignored)
     * @param batchId   batch (= Delta session) identifier
     * @param tableName table whose delta file to download
     * @return presigned download, or empty when the batch has nothing to build for that table
     *         (unknown table, unfinished batch, no published segments) or finalization gave up
     * @throws BatchParquetNotReadyException while the artifact is queued, building, or awaiting
     *         another attempt
     */
    // No @Transactional: the DB reads run in their own transactions and are materialized before
    // the S3 HEAD/presign round-trips — holding a HikariCP connection across network calls would
    // starve the pool under S3 latency.
    public Optional<PresignedDownload> presignBatchTableParquet(UUID siteId, UUID batchId, String tableName) {
        Optional<BatchParquetArtifact> found = artifactRepository
                .findBySiteIdAndBatchIdAndTableName(siteId, batchId, tableName);
        if (found.isEmpty()) {
            found = backfill(siteId, batchId, tableName);
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BatchParquetArtifact artifact = found.get();
        // A FAILED artifact still has attempts left, so the answer is "not yet", not "not there":
        // reporting a transient upload or segment-read failure as 404 would tell the user the table
        // has no file at all, minutes before the same click starts working.
        if (artifact.isRetryable()) {
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

    /**
     * Enqueue a batch whose manifest was never written — the batches that completed before 036
     * shipped. Their raw segments are intact, so the artifact is buildable; the first click queues
     * it (answering 409) and a later one downloads it. Returns empty when there is nothing to
     * build, which keeps the existing 404 for an unknown table or a batch without segments.
     */
    private Optional<BatchParquetArtifact> backfill(UUID siteId, UUID batchId, String tableName) {
        // Deliberately ignore the created count. The enqueue is insert-if-absent, so a request that
        // loses the race to a concurrent one creates 0 rows even though the work is now queued;
        // answering 404 off that count would contradict the readiness contract. Only the row is
        // evidence — its absence still separates an unknown table / unfinished batch (404) from
        // queued work (409).
        finalizationService.enqueueBatchForSite(siteId, batchId);
        Optional<BatchParquetArtifact> queued = artifactRepository
                .findBySiteIdAndBatchIdAndTableName(siteId, batchId, tableName);
        if (queued.isPresent()) {
            finalizationWorker.wake();
        }
        return queued;
    }

    /** A manifest exists, but S3 publication has not completed yet. */
    public static class BatchParquetNotReadyException extends RuntimeException {
        public BatchParquetNotReadyException(UUID batchId, String tableName) {
            super("Unified Parquet finalization is still in progress for batch " + batchId
                    + ", table " + tableName);
        }
    }
}
