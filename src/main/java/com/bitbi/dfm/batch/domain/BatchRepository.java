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

    Page<Batch> findBySiteIdAndStatus(UUID siteId, BatchStatus status, Pageable pageable);

    Batch save(Batch batch);

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
}
