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
     * Count active IN_PROGRESS batches for account.
     * <p>
     * Used to enforce account-level active batch limit.
     * </p>
     *
     * @param accountId account identifier
     * @return number of active batches
     */
    @Query("SELECT COUNT(b) FROM Batch b " +
            "JOIN Site s ON b.siteId = s.id " +
            "WHERE s.accountId = :accountId AND b.status = 'IN_PROGRESS'")
    int countActiveBatchesByAccountId(UUID accountId);
}
