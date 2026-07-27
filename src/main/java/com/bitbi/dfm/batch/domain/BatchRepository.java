package com.bitbi.dfm.batch.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Batch aggregate.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface BatchRepository {

    Optional<Batch> findById(UUID id);

    Optional<Batch> findActiveBySiteId(UUID siteId);

    List<Batch> findExpiredBatches(LocalDateTime cutoffTime);

    List<Batch> findCleanupCandidatesForSite(UUID siteId, LocalDateTime cutoffTime, int limit);

    Page<Batch> findBySiteIdAndStatus(UUID siteId, BatchStatus status, Pageable pageable);

    Batch save(Batch batch);

    /**
     * Stamp a batch's last session activity (029/030) with a targeted update.
     * <p>
     * Deliberately not a load-modify-{@link #save(Batch)}: {@code Batch} carries a {@code @Version},
     * so a read-modify-write liveness signal takes part in optimistic locking and loses races
     * against real transitions (the timeout sweeper, a segment commit) — throwing
     * {@code OptimisticLockingFailureException} into the Delta v2 gRPC ingest path and killing a
     * healthy live session. A single-column update carries no version check, so the liveness write
     * cannot conflict with anything.
     * </p>
     *
     * @param batchId batch identifier
     * @param now     activity timestamp
     * @return number of rows updated (0 when the batch no longer exists)
     */
    int touchActivity(UUID batchId, LocalDateTime now);

    long countByAccountId(UUID accountId);

    long countBySiteId(UUID siteId);

    long countActiveBatches();

    int countActiveBatchesByAccountId(UUID accountId);

    int countActiveBatchesByAccountIdWithLock(UUID accountId);

    long count();

    void deleteById(UUID id);

    Page<Batch> findBySiteId(UUID siteId, Pageable pageable);

    Page<Batch> findByStatus(BatchStatus status, Pageable pageable);

    Page<Batch> findAll(Pageable pageable);

    boolean existsById(UUID id);

    /**
     * Finds the most recent completed batch for a site, excluding a specific batch.
     * Used by SQL generation to find the previous batch for comparison.
     *
     * @param siteId The site ID to find batches for
     * @param excludeBatchId The batch ID to exclude (typically the current batch)
     * @return Optional containing the most recent completed batch
     */
    Optional<Batch> findPreviousBatchForSite(UUID siteId, UUID excludeBatchId);

    /**
     * Finds a batch by ID with uploaded files eagerly loaded (JOIN FETCH).
     * Prevents N+1 query problem when accessing files.
     *
     * @param batchId The batch ID
     * @return Optional containing the batch with files
     */
    Optional<Batch> findByIdWithFiles(UUID batchId);

    /**
     * Finds the previous batch for a site with uploaded files eagerly loaded.
     * Used by SQL generation for efficient file comparison.
     *
     * @param siteId The site ID
     * @param excludeBatchId The batch ID to exclude
     * @return Optional containing the previous batch with files
     */
    Optional<Batch> findPreviousBatchForSiteWithFiles(UUID siteId, UUID excludeBatchId);

    /**
     * Finds the most recent completed batch for an account across all sites.
     * Used by plugin initialization to find the batch to generate SQL from.
     *
     * @param accountId The account ID
     * @return Optional containing the most recent completed batch, or empty if none
     */
    Optional<Batch> findLatestCompletedByAccountId(UUID accountId);

    /**
     * Finds all completed batches for an account with pagination.
     * Used by admin to find batches that may need SQL generation.
     *
     * @param accountId The account ID
     * @param pageable pagination parameters
     * @return Page of completed batches
     */
    Page<Batch> findCompletedByAccountId(UUID accountId, Pageable pageable);

    /**
     * Finds completed batches for an account optionally filtered by site.
     * Used for user-facing batch listing with optional site filter.
     *
     * @param accountId The account ID
     * @param siteId Optional site ID filter (null for all sites)
     * @param pageable pagination parameters
     * @return Page of completed batches
     */
    Page<Batch> findCompletedByAccountIdAndOptionalSiteId(UUID accountId, UUID siteId, Pageable pageable);
}
