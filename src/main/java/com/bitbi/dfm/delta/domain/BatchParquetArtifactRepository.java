package com.bitbi.dfm.delta.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * <p>The head of the queue is per site, in batch order (issues #378, #380): a row is held back
     * while an earlier batch of its site still has an unfinished row, or is still owed its rows
     * altogether (see {@link #findBatchesAwaitingEnqueue}).</p>
     *
     * @param now                current instant, the reference for both delays
     * @param retryDelaySeconds  base failure backoff; doubles per attempt, capped
     * @param leaseSeconds       how long a {@code BUILDING} claim is honoured before it is reclaimed
     * @param maxAttempts        spent expired claims are settled separately, never reclaimed
     * @param enqueueHorizon     earliest {@code started_at} of a batch still owed its rows that
     *                           holds its site back; the same bound the sweep uses
     * @param limit              maximum rows to claim
     */
    List<BatchParquetArtifact> findNextRetryable(LocalDateTime now, int retryDelaySeconds,
                                                 int leaseSeconds, int maxAttempts,
                                                 LocalDateTime enqueueHorizon, int limit);

    /**
     * Completed batches still owed their work rows (issue #380): {@code COMPLETED} or
     * {@code COMPLETED_WITH_WARNINGS} — the statuses whose commit enqueues them after the fact —
     * with at least one published changelog segment and no row here at all, started at or after
     * {@code enqueueHorizon}. The rows are created {@code AFTER_COMMIT}, so a process that dies in
     * that window, or an enqueue that throws, leaves exactly such a batch. The claim query holds
     * the batch's site back behind the same predicate, so the two cannot disagree.
     *
     * @param enqueueHorizon  earliest {@code started_at} considered
     * @param completedBefore only batches completed strictly before this instant, so a batch whose
     *                        own enqueue may still be running is left to it
     * @param limit           maximum batches returned
     * @return batch ids in batch order, {@code (started_at, id)}
     */
    List<UUID> findBatchesAwaitingEnqueue(LocalDateTime enqueueHorizon, LocalDateTime completedBefore,
                                          int limit);

    /**
     * Serialize the short claim transaction for one batch. The lock is transaction-scoped, so the
     * caller must claim every retryable sibling before committing.
     *
     * @return false when another transaction is already claiming the same batch
     */
    boolean tryLockBatch(UUID batchId);

    /**
     * Serialize catalog-visible publication ({@code READY}/{@code ABANDONED}) so {@code ready_at}
     * / {@code updated_at} are assigned after the previous publisher has committed. Without this,
     * a slower transaction can stamp an earlier wall-clock and vanish from {@code since}.
     */
    void lockCatalogPublish();

    /**
     * Next catalog-visible timestamp: {@code max(previous + 1µs, clock_timestamp())}. Must run
     * in the same transaction as {@link #lockCatalogPublish()} so two publishers cannot observe
     * the same watermark. Survives pod clock skew that a timestamp taken in the JVM would not.
     */
    LocalDateTime nextCatalogWatermark();

    /** Retryable siblings of a selected batch, locked for the duration of the claim transaction. */
    List<BatchParquetArtifact> findRetryableByBatchId(UUID batchId, LocalDateTime now,
                                                      int retryDelaySeconds, int leaseSeconds,
                                                      int maxAttempts);

    /**
     * Cheap probe for the work {@link #abandonExpiredClaims} would do. A worker polls the queue
     * far more often than a claim actually dies, and taking the catalog lock plus a watermark row
     * update to discover "nothing to settle" serializes every idle poll on one cluster-wide lock
     * and writes a dead tuple into a single-row table. Reading the same predicate first costs one
     * index probe and lets the settle transaction start only when it has something to stamp.
     *
     * @return true when at least one row would be moved to {@code ABANDONED}
     */
    boolean hasSpentExpiredClaims(LocalDateTime now, int leaseSeconds, int maxAttempts);

    /**
     * Settle expired claims whose owners never returned and whose attempt budget is spent.
     *
     * @return number of rows moved to {@code ABANDONED}
     */
    int abandonExpiredClaims(LocalDateTime now, LocalDateTime publishedAt, int leaseSeconds,
                             int maxAttempts, String error);

    /**
     * Create one PENDING work row unless it already exists. Idempotent by
     * {@code (batch_id, table_name)} so concurrent enqueues of the same batch cannot collide.
     *
     * @return 1 when a row was created, 0 when one already existed
     */
    int insertPendingIfAbsent(UUID id, UUID batchId, UUID siteId, String tableName, LocalDateTime now);

    /**
     * Which of these batches still owe a completed-batch Parquet build (issue #244).
     *
     * <p>Changelog retention's census read: a batch with a row in
     * {@link BatchParquetArtifactStatus#UNFINISHED} still replays its raw segments on the next
     * attempt, so those segments are held back. One query per prune pass over the candidate batch
     * ids — the decision is per batch, never per segment, because a partial prune would leave the
     * replay silently truncated rather than failed.</p>
     *
     * @param batchIds candidate batches (the segments the audit window would prune)
     * @param statuses the statuses that still owe a build ({@code UNFINISHED})
     * @return the subset of {@code batchIds} carrying at least one artifact row in {@code statuses}
     */
    Set<UUID> findBatchIdsWithStatusIn(Collection<UUID> batchIds,
                                       Collection<BatchParquetArtifactStatus> statuses);

    /** Recorded keys of a site's published artifacts — the wipe's exact-key fallback. */
    List<String> findS3KeysBySiteId(UUID siteId);

    int deleteByBatchId(UUID batchId);

    int deleteBySiteId(UUID siteId);
}
