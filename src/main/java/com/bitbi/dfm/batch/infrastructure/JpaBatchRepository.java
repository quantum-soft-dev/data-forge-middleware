package com.bitbi.dfm.batch.infrastructure;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
     * Find all expired IN_PROGRESS batches.
     * <p>
     * Used by BatchTimeoutScheduler to mark batches as NOT_COMPLETED.
     * </p>
     *
     * @param cutoffTime batches started before this time are considered expired
     * @return list of expired batches
     */
    @Query("SELECT b FROM Batch b WHERE b.status = 'IN_PROGRESS' AND b.startedAt < :cutoffTime")
    List<Batch> findExpiredBatches(LocalDateTime cutoffTime);

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
          AND (CAST(:siteId AS UUID) IS NULL OR b.site_id = :siteId)
        ORDER BY b.completed_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM batches b
        WHERE b.account_id = :accountId
          AND b.status IN ('COMPLETED', 'COMPLETED_WITH_WARNINGS')
          AND (CAST(:siteId AS UUID) IS NULL OR b.site_id = :siteId)
        """,
        nativeQuery = true)
    Page<Batch> findCompletedByAccountIdAndOptionalSiteId(UUID accountId, UUID siteId, Pageable pageable);
}
