package com.bitbi.dfm.error.infrastructure;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JPA implementation of ErrorLogRepository.
 * <p>
 * Works with partitioned error_logs table and supports JSONB metadata queries.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaErrorLogRepository extends JpaRepository<ErrorLog, UUID>, ErrorLogRepository {

    /**
     * Find errors by site with pagination.
     *
     * @param siteId site identifier
     * @param pageable pagination parameters
     * @return page of error logs
     */
    @Query("SELECT e FROM ErrorLog e WHERE e.siteId = :siteId ORDER BY e.occurredAt DESC")
    Page<ErrorLog> findBySiteId(UUID siteId, Pageable pageable);

    /**
     * Find errors by type and date range with pagination.
     * <p>
     * Efficiently uses partition pruning when date range is within single month.
     * </p>
     *
     * @param type error type
     * @param start start date (inclusive)
     * @param end end date (exclusive)
     * @param pageable pagination parameters
     * @return page of error logs
     */
    @Query("SELECT e FROM ErrorLog e " +
            "WHERE e.type = :type " +
            "AND e.occurredAt >= :start " +
            "AND e.occurredAt < :end " +
            "ORDER BY e.occurredAt DESC")
    Page<ErrorLog> findByTypeAndOccurredAtBetween(String type, LocalDateTime start,
                                                    LocalDateTime end, Pageable pageable);

    /**
     * Find errors by batch ID.
     *
     * @param batchId batch identifier
     * @return list of error logs
     */
    @Query("SELECT e FROM ErrorLog e WHERE e.batchId = :batchId ORDER BY e.occurredAt DESC")
    List<ErrorLog> findByBatchId(UUID batchId);

    /**
     * Find errors by site ID.
     *
     * @param siteId site identifier
     * @return list of error logs
     */
    @Query("SELECT e FROM ErrorLog e WHERE e.siteId = :siteId ORDER BY e.occurredAt DESC")
    List<ErrorLog> findBySiteId(UUID siteId);

    /**
     * Export errors by filters without pagination.
     * <p>
     * Used for CSV export functionality.
     * </p>
     *
     * @param siteId site identifier (nullable)
     * @param type error type (nullable)
     * @param start start date (nullable)
     * @param end end date (nullable)
     * @return list of error logs matching filters
     */
    @Query("SELECT e FROM ErrorLog e " +
            "WHERE (:siteId IS NULL OR e.siteId = :siteId) " +
            "AND (:type IS NULL OR e.type = :type) " +
            "AND (:start IS NULL OR e.occurredAt >= :start) " +
            "AND (:end IS NULL OR e.occurredAt < :end) " +
            "ORDER BY e.occurredAt DESC")
    List<ErrorLog> exportByFilters(UUID siteId, String type, LocalDateTime start, LocalDateTime end);

    /**
     * Count errors by batch ID.
     *
     * @param batchId batch identifier
     * @return number of errors
     */
    @Query("SELECT COUNT(e) FROM ErrorLog e WHERE e.batchId = :batchId")
    long countByBatchId(UUID batchId);

    /**
     * Count errors by site ID.
     *
     * @param siteId site identifier
     * @return number of errors
     */
    @Query("SELECT COUNT(e) FROM ErrorLog e WHERE e.siteId = :siteId")
    long countBySiteId(UUID siteId);

    /**
     * Count errors by multiple site IDs.
     *
     * @param siteIds list of site identifiers
     * @return number of errors
     */
    @Query("SELECT COUNT(e) FROM ErrorLog e WHERE e.siteId IN :siteIds")
    long countBySiteIds(List<UUID> siteIds);
}
