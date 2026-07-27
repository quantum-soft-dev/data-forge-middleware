package com.bitbi.dfm.batch.infrastructure;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of BatchRepository.
 * <p>
 * Includes custom queries for active batch lookup and expired batch detection.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaBatchRepository extends JpaRepository<Batch, UUID>, BatchRepository {

    /**
     * Find active (IN_PROGRESS) batch for site.
     * <p>
     * Enforces "one active batch per site" constraint.
     * </p>
     *
     * @param siteId site identifier
     * @return Optional containing active batch if exists
     */
    @Query("SELECT b FROM Batch b WHERE b.siteId = :siteId AND b.status = 'IN_PROGRESS'")
    Optional<Batch> findActiveBySiteId(UUID siteId);

    /**
     * Stamp the batch's last session activity (029, made lock-free in 030).
     * <p>
     * A JPQL bulk update writes the column directly: it neither loads the aggregate nor bumps
     * {@code @Version} (only {@code UPDATE VERSIONED} would), so the Delta v2 liveness signal never
     * competes for the version with a concurrent transition — the timeout sweeper marking the batch
     * NOT_COMPLETED, a segment commit, an error flag. Before this, the loser of that race threw
     * {@code OptimisticLockingFailureException} straight into the gRPC ingest path.
     * </p>
     * <p>
     * Carries its own {@code @Transactional} so the ingest path (no ambient transaction) gets a
     * self-contained write whose failure rolls back cleanly, leaving nothing for the best-effort
     * caller to poison.
     * </p>
     * <p>
     * <b>Must stay version-free</b>, unlike {@link #markNotCompletedIfStillExpired}, which bumps it.
     * A liveness stamp records that a session is alive; it does not change the batch's state, so it
     * has no business in the optimistic-locking protocol. Adding a bump here restores the original
     * 030 bug: the touch starts losing races against real transitions and throws
     * {@code OptimisticLockingFailureException} into the gRPC ingest path, killing live sessions.
     * </p>
     *
     * <p>
     * Restricted to IN_PROGRESS batches (031/T10): a gRPC frame that arrives after the session's
     * batch was completed, failed or reaped would otherwise stamp fresh activity onto a terminal
     * row. The sweeper filters on IN_PROGRESS so nothing misbehaved, but the row then carried
     * evidence of a live session that had already ended — misleading anyone reading it during an
     * incident review.
     * </p>
     *
     * @param batchId batch identifier
     * @param now     activity timestamp
     * @return number of rows updated (0 when the batch is gone or no longer IN_PROGRESS)
     */
    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.lastActivityAt = :now WHERE b.id = :batchId "
            + "AND b.status = com.bitbi.dfm.batch.domain.BatchStatus.IN_PROGRESS")
    int touchActivity(UUID batchId, LocalDateTime now);

    /**
     * Reap an expired batch, but only while it is <em>still</em> expired and IN_PROGRESS (030/T06).
     * <p>
     * The status and cutoff predicates make the sweeper's transition atomic with its own decision.
     * Between the sweeper's SELECT and this UPDATE a live Delta v2 session may touch the batch, and
     * killing it then is precisely the incident 029 set out to prevent. PostgreSQL's READ COMMITTED
     * re-evaluates the WHERE clause after waiting on a concurrent writer's row lock, so a touch
     * that lands first turns this into a 0-row no-op instead of a lost live session.
     * </p>
     * <p>
     * Mirrors {@link #findExpiredBatches}' {@code COALESCE(lastActivityAt, startedAt)} so v1
     * batches (which never record activity) keep their started_at-based expiry.
     * </p>
     * <p>
     * <b>This one bumps {@code version}; {@link #touchActivity} deliberately does not. Do not
     * "unify" the two queries.</b> A JPQL bulk update does not touch {@code @Version} by itself, and
     * the difference is not an oversight:
     * </p>
     * <ul>
     *   <li>This is a <em>state transition</em>. Every other transition
     *       ({@code completeBatch}, {@code failBatch}, {@code cancelBatch}) is a read-modify-write
     *       guarded by {@code @Version}. Without the bump here, a session that loaded the batch
     *       before this UPDATE still matched on its stale version and flushed {@code COMPLETED} over
     *       the reap — the batch ended COMPLETED after {@code BatchExpiredEvent} had already been
     *       dispatched, two terminal events for one batch (031/T09). Bumping makes that stale flush
     *       fail with {@code OptimisticLockingFailureException}, which is the correct outcome: the
     *       batch was legitimately reclaimed.</li>
     *   <li>{@link #touchActivity} is <em>not</em> a state transition — it records liveness. Giving
     *       it a version bump is what made it collide with real transitions and throw into the gRPC
     *       ingest path, killing live sessions (030/T01). It must stay version-free.</li>
     * </ul>
     *
     * @param batchId    batch identifier
     * @param cutoffTime the cutoff the sweeper's SELECT used
     * @param now        completion timestamp
     * @return 1 when reaped, 0 when revived or already terminal
     */
    @Modifying
    @Transactional
    @Query("UPDATE Batch b SET b.status = com.bitbi.dfm.batch.domain.BatchStatus.NOT_COMPLETED, "
            + "b.completedAt = :now, b.version = b.version + 1 "
            + "WHERE b.id = :batchId "
            + "AND b.status = com.bitbi.dfm.batch.domain.BatchStatus.IN_PROGRESS "
            + "AND COALESCE(b.lastActivityAt, b.startedAt) < :cutoffTime")
    int markNotCompletedIfStillExpired(UUID batchId, LocalDateTime cutoffTime, LocalDateTime now);

    /**
     * Find all expired IN_PROGRESS batches.
     * <p>
     * Used by BatchTimeoutScheduler to mark batches as NOT_COMPLETED.
     * </p>
     *
     * <p>029: expiry is measured from the last session activity when present
     * ({@code COALESCE(last_activity_at, started_at)}). A live Delta v2 streaming session touches
     * its batch continuously (session start, ack watermark, seals), so it can legitimately run past
     * the timeout without being reaped — while a session silent for the whole timeout window is
     * failed, freeing the site. v1 batches never have activity, keeping their started_at-based
     * timeout. This replaces the blanket V2 exclusion (review r4): the exclusion existed only
     * because started_at-based expiry would kill long live sessions mid-commit.</p>
     *
     * @param cutoffTime batches whose last activity (or start, when no activity) precedes this time
     * @return list of expired batches
     */
    @Query("SELECT b FROM Batch b WHERE b.status = 'IN_PROGRESS' "
            + "AND COALESCE(b.lastActivityAt, b.startedAt) < :cutoffTime")
    List<Batch> findExpiredBatches(LocalDateTime cutoffTime);

    /**
     * Find cleanup candidates for a site based on retention cutoff.
     * <p>
     * Excludes IN_PROGRESS batches and selects oldest first.
     * Uses SKIP LOCKED to avoid concurrent cleanup collisions.
     * </p>
     *
     * @param siteId site identifier
     * @param cutoffTime batches started before this time are eligible
     * @param limit max number of batches to return
     * @return list of cleanup candidates
     */
    @Query(value = """
        SELECT * FROM batches
        WHERE site_id = :siteId
          AND status <> 'IN_PROGRESS'
          AND started_at < :cutoffTime
          AND NOT EXISTS (
              SELECT 1
              FROM account_plugins ap
              WHERE ap.baseline_batch_id = batches.id
          )
        ORDER BY started_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Batch> findCleanupCandidatesForSite(UUID siteId, LocalDateTime cutoffTime, int limit);

    /**
     * Find batches by site and status with pagination.
     *
     * @param siteId site identifier
     * @param status batch status
     * @param pageable pagination parameters
     * @return page of batches
     */
    @Query("SELECT b FROM Batch b WHERE b.siteId = :siteId AND b.status = :status ORDER BY b.startedAt DESC")
    Page<Batch> findBySiteIdAndStatus(UUID siteId, BatchStatus status, Pageable pageable);

    /**
     * Count all batches for account.
     *
     * @param accountId account identifier
     * @return number of batches
     */
    @Query("SELECT COUNT(b) FROM Batch b WHERE b.accountId = :accountId")
    long countByAccountId(UUID accountId);

    /**
     * Count batches by site ID.
     *
     * @param siteId site identifier
     * @return number of batches
     */
    @Query("SELECT COUNT(b) FROM Batch b WHERE b.siteId = :siteId")
    long countBySiteId(UUID siteId);

    /**
     * Count all active IN_PROGRESS batches across all sites.
     *
     * @return number of active batches
     */
    @Query("SELECT COUNT(b) FROM Batch b WHERE b.status = 'IN_PROGRESS'")
    long countActiveBatches();

    /**
     * Count active IN_PROGRESS batches for account.
     * <p>
     * Used to enforce account-level active batch limit.
     * Note: This method has a race condition. Use countActiveBatchesByAccountIdWithLock for atomic operations.
     * </p>
     *
     * @param accountId account identifier
     * @return number of active batches
     */
    @Query("SELECT COUNT(b) FROM Batch b WHERE b.accountId = :accountId AND b.status = 'IN_PROGRESS'")
    int countActiveBatchesByAccountId(UUID accountId);

    /**
     * Count active IN_PROGRESS batches for account with pessimistic lock.
     * <p>
     * Uses FOR UPDATE lock on the batches table to prevent race conditions during batch creation.
     * The lock is acquired by selecting the rows first, then counting them.
     * Should be called within a transaction.
     * </p>
     *
     * @param accountId account identifier
     * @return number of active batches
     */
    @Query(value = "SELECT COUNT(*) FROM (SELECT id FROM batches WHERE account_id = :accountId AND status = 'IN_PROGRESS' FOR UPDATE) AS locked_batches", nativeQuery = true)
    int countActiveBatchesByAccountIdWithLock(UUID accountId);

    /**
     * Find batches by site with pagination.
     *
     * @param siteId site identifier
     * @param pageable pagination parameters
     * @return page of batches
     */
    @Query("SELECT b FROM Batch b WHERE b.siteId = :siteId ORDER BY b.startedAt DESC")
    Page<Batch> findBySiteId(UUID siteId, Pageable pageable);

    /**
     * Find batches by status with pagination.
     *
     * @param status batch status
     * @param pageable pagination parameters
     * @return page of batches
     */
    @Query("SELECT b FROM Batch b WHERE b.status = :status ORDER BY b.startedAt DESC")
    Page<Batch> findByStatus(BatchStatus status, Pageable pageable);

    /**
     * T025: Find first page of batches for user's sites with DTO projection.
     * <p>
     * Cursor-based pagination - first page query (no cursor).
     * Returns batches ordered by startedAt DESC, id DESC for stable sorting.
     * </p>
     *
     * @param siteIds list of site IDs owned by user
     * @param limit maximum number of results
     * @return list of batch projections
     */
    @Query("""
        SELECT b.id as id, b.siteId as siteId, b.status as status,
               b.hasErrors as hasErrors, b.startedAt as startedAt,
               b.completedAt as completedAt, b.uploadedFilesCount as fileCount,
               b.totalSize as totalSize
        FROM Batch b
        WHERE b.siteId IN :siteIds
        ORDER BY b.startedAt DESC, b.id DESC
        LIMIT :limit
        """)
    List<BatchWithFileCountProjection> findBySiteIdsFirstPage(List<UUID> siteIds, int limit);

    /**
     * T025: Find batches with cursor for pagination.
     * <p>
     * Cursor-based pagination - subsequent pages using cursor (startedAt + id).
     * Uses composite condition for stable pagination across data changes.
     * </p>
     *
     * @param siteIds list of site IDs owned by user
     * @param cursorStartedAt startedAt value from cursor
     * @param cursorId id value from cursor
     * @param limit maximum number of results
     * @return list of batch projections
     */
    @Query("""
        SELECT b.id as id, b.siteId as siteId, b.status as status,
               b.hasErrors as hasErrors, b.startedAt as startedAt,
               b.completedAt as completedAt, b.uploadedFilesCount as fileCount,
               b.totalSize as totalSize
        FROM Batch b
        WHERE b.siteId IN :siteIds
          AND (b.startedAt < :cursorStartedAt
               OR (b.startedAt = :cursorStartedAt AND b.id < :cursorId))
        ORDER BY b.startedAt DESC, b.id DESC
        LIMIT :limit
        """)
    List<BatchWithFileCountProjection> findBySiteIdsWithCursor(
        List<UUID> siteIds,
        LocalDateTime cursorStartedAt,
        UUID cursorId,
        int limit
    );

    /**
     * T045: Find batch by ID with all uploaded files eagerly loaded.
     * <p>
     * Uses LEFT JOIN FETCH to avoid N+1 query problem.
     * All files are loaded in a single SQL query.
     * </p>
     *
     * @param batchId batch identifier
     * @return Optional containing batch with files, or empty if not found
     */
    @Query("""
        SELECT b
        FROM Batch b
        LEFT JOIN FETCH b.uploadedFiles
        WHERE b.id = :batchId
        """)
    Optional<Batch> findByIdWithFiles(UUID batchId);

    /**
     * Finds the most recent completed batch for a site, excluding a specific batch.
     * Used by SQL generation to find the previous batch for comparison.
     * <p>
     * Only considers COMPLETED or COMPLETED_WITH_WARNINGS batches as valid previous batches.
     * </p>
     *
     * @param siteId The site ID to find batches for
     * @param excludeBatchId The batch ID to exclude (typically the current batch)
     * @return Optional containing the most recent completed batch
     */
    @Query("""
        SELECT b
        FROM Batch b
        WHERE b.siteId = :siteId
          AND b.id <> :excludeBatchId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
        ORDER BY b.completedAt DESC
        LIMIT 1
        """)
    Optional<Batch> findPreviousBatchForSite(UUID siteId, UUID excludeBatchId);

    /**
     * Finds the previous batch for a site with uploaded files eagerly loaded.
     * <p>
     * Uses LEFT JOIN FETCH to avoid N+1 query problem.
     * Only considers COMPLETED or COMPLETED_WITH_WARNINGS batches.
     * </p>
     *
     * @param siteId The site ID to find batches for
     * @param excludeBatchId The batch ID to exclude (typically the current batch)
     * @return Optional containing the previous batch with files
     */
    @Query("""
        SELECT b
        FROM Batch b
        LEFT JOIN FETCH b.uploadedFiles
        WHERE b.siteId = :siteId
          AND b.id <> :excludeBatchId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
        ORDER BY b.completedAt DESC
        LIMIT 1
        """)
    Optional<Batch> findPreviousBatchForSiteWithFiles(UUID siteId, UUID excludeBatchId);

    /**
     * Finds the most recent completed batch for an account across all sites.
     * <p>
     * Used by plugin initialization to find the batch to generate SQL from.
     * Only considers COMPLETED or COMPLETED_WITH_WARNINGS batches.
     * </p>
     *
     * @param accountId The account ID
     * @return Optional containing the most recent completed batch, or empty if none
     */
    @Query("""
        SELECT b
        FROM Batch b
        WHERE b.accountId = :accountId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
        ORDER BY b.completedAt DESC
        LIMIT 1
        """)
    Optional<Batch> findLatestCompletedByAccountId(UUID accountId);

    /**
     * Finds all completed batches for an account with pagination.
     * <p>
     * Used by admin to list batches that may need SQL generation.
     * Only considers COMPLETED or COMPLETED_WITH_WARNINGS batches.
     * </p>
     *
     * @param accountId The account ID
     * @param pageable pagination parameters
     * @return Page of completed batches
     */
    @Query("""
        SELECT b
        FROM Batch b
        WHERE b.accountId = :accountId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
        ORDER BY b.completedAt DESC
        """)
    Page<Batch> findCompletedByAccountId(UUID accountId, Pageable pageable);

    /**
     * Finds completed batches for an account optionally filtered by site.
     * <p>
     * Used for user-facing batch listing with optional site filter.
     * Only considers COMPLETED or COMPLETED_WITH_WARNINGS batches.
     * </p>
     *
     * @param accountId The account ID
     * @param siteId Optional site ID filter (null for all sites)
     * @param pageable pagination parameters
     * @return Page of completed batches
     */
    @Query(value = """
        SELECT * FROM batches b
        WHERE b.account_id = :accountId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
          AND (:siteId IS NULL OR b.site_id = CAST(:siteId AS UUID))
        ORDER BY b.completed_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM batches b
        WHERE b.account_id = :accountId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
          AND (:siteId IS NULL OR b.site_id = CAST(:siteId AS UUID))
        """,
        nativeQuery = true)
    Page<Batch> findCompletedByAccountIdAndOptionalSiteId(UUID accountId, UUID siteId, Pageable pageable);
}
