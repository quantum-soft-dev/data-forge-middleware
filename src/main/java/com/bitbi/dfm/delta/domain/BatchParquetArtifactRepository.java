package com.bitbi.dfm.delta.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

/** Persistence contract for the completed-batch Parquet manifest and work queue. */
public interface BatchParquetArtifactRepository {

    BatchParquetArtifact save(BatchParquetArtifact artifact);

    /** Re-read a claimed row once its build finished, to settle the outcome against current state. */
    Optional<BatchParquetArtifact> findById(UUID id);

    /**
     * Renew a live claim's lease. Without this the lease would be a ceiling on build time rather
     * than a liveness signal, and any build slower than it would be duplicated by the next worker.
     *
     * @return 1 when the row is still {@code BUILDING} under this token, 0 once it was taken over
     */
    int touchClaim(UUID id, UUID claimToken, LocalDateTime now);

    Optional<BatchParquetArtifact> findBySiteIdAndBatchIdAndTableName(
            UUID siteId, UUID batchId, String tableName);

    List<BatchParquetArtifact> findByBatchId(UUID batchId);

    /**
     * Work worth another attempt: never-claimed rows, failures whose backoff has elapsed, and
     * claims whose owner disappeared (their build lease expired). {@code ABANDONED} rows are
     * terminal and never returned — several failure classes are deterministic (a table with no
     * declared schema, data the schema cannot render, a batch whose segments a re-baseline removed)
     * and would otherwise be rebuilt forever.
     *
     * @param now                current instant, the reference for both delays
     * @param retryDelaySeconds  base failure backoff; doubles per attempt, capped
     * @param leaseSeconds       how long a {@code BUILDING} claim is honoured before it is reclaimed
     * @param limit              maximum rows to claim
     */
    List<BatchParquetArtifact> findNextRetryable(LocalDateTime now, int retryDelaySeconds,
                                                 int leaseSeconds, int limit);

    List<String> findS3KeysByBatchId(UUID batchId);

    List<String> findS3KeysBySiteId(UUID siteId);

    int deleteByBatchId(UUID batchId);

    int deleteBySiteId(UUID siteId);
}
