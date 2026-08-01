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

    /** Operator projection, constrained by both route owners and sorted for stable responses. */
    List<BatchParquetArtifact> findBySiteIdAndBatchIdOrderByTableName(UUID siteId, UUID batchId);

    /** Lock one route-constrained artifact so recovery cannot race a worker settlement. */
    Optional<BatchParquetArtifact> findForUpdate(UUID artifactId, UUID siteId, UUID batchId);

    /** All durable queue depths in one grouped query; absent statuses have zero rows. */
    List<BatchParquetQueueDepth> countByStatusGrouped();

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
     * @param maxAttempts        spent expired claims are settled separately, never reclaimed
     * @param limit              maximum rows to claim
     */
    List<BatchParquetArtifact> findNextRetryable(LocalDateTime now, int retryDelaySeconds,
                                                 int leaseSeconds, int maxAttempts, int limit);

    /**
     * Serialize the short claim transaction for one batch. The lock is transaction-scoped, so the
     * caller must claim every retryable sibling before committing.
     *
     * @return false when another transaction is already claiming the same batch
     */
    boolean tryLockBatch(UUID batchId);

    /** Retryable siblings of a selected batch, locked for the duration of the claim transaction. */
    List<BatchParquetArtifact> findRetryableByBatchId(UUID batchId, LocalDateTime now,
                                                      int retryDelaySeconds, int leaseSeconds,
                                                      int maxAttempts);

    /**
     * Settle expired claims whose owners never returned and whose attempt budget is spent.
     *
     * @return number of rows moved to {@code ABANDONED}
     */
    int abandonExpiredClaims(LocalDateTime now, int leaseSeconds, int maxAttempts, String error);

    /**
     * Create one PENDING work row unless it already exists. Idempotent by
     * {@code (batch_id, table_name)} so concurrent enqueues of the same batch cannot collide.
     *
     * @return 1 when a row was created, 0 when one already existed
     */
    int insertPendingIfAbsent(UUID id, UUID batchId, UUID siteId, String tableName, LocalDateTime now);

    /** Recorded keys of a site's published artifacts — the wipe's exact-key fallback. */
    List<String> findS3KeysBySiteId(UUID siteId);

    int deleteByBatchId(UUID batchId);

    int deleteBySiteId(UUID siteId);
}
