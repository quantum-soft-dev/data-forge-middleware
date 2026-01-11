package com.bitbi.dfm.error.infrastructure;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.error.domain.GlobalErrorProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
     * Find errors by site and type with pagination.
     *
     * @param siteId site identifier
     * @param type error type
     * @param pageable pagination parameters
     * @return page of error logs
     */
    @Query("SELECT e FROM ErrorLog e WHERE e.siteId = :siteId AND e.type = :type ORDER BY e.occurredAt DESC")
    Page<ErrorLog> findBySiteIdAndType(UUID siteId, String type, Pageable pageable);

    /**
     * Find errors by type with pagination.
     *
     * @param type error type
     * @param pageable pagination parameters
     * @return page of error logs
     */
    @Query("SELECT e FROM ErrorLog e WHERE e.type = :type ORDER BY e.occurredAt DESC")
    Page<ErrorLog> findByType(String type, Pageable pageable);

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
            "AND (CAST(:start AS timestamp) IS NULL OR e.occurredAt >= :start) " +
            "AND (CAST(:end AS timestamp) IS NULL OR e.occurredAt < :end) " +
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

    // ==================== Global Error Handling Methods ====================

    /**
     * Find global errors (batch_id IS NULL) for account with pagination.
     * <p>
     * Joins with sites table to find errors for all sites belonging to the account.
     * </p>
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of global error logs
     */
    @Query("SELECT e FROM ErrorLog e " +
            "JOIN com.bitbi.dfm.site.domain.Site s ON e.siteId = s.id " +
            "WHERE s.accountId = :accountId AND e.batchId IS NULL " +
            "ORDER BY e.occurredAt DESC")
    Page<ErrorLog> findGlobalErrorsByAccountId(UUID accountId, Pageable pageable);

    /**
     * Find unread global errors (batch_id IS NULL AND is_read = false) for account.
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of unread global error logs
     */
    @Query("SELECT e FROM ErrorLog e " +
            "JOIN com.bitbi.dfm.site.domain.Site s ON e.siteId = s.id " +
            "WHERE s.accountId = :accountId AND e.batchId IS NULL AND e.isRead = false " +
            "ORDER BY e.occurredAt DESC")
    Page<ErrorLog> findGlobalErrorsByAccountIdAndUnread(UUID accountId, Pageable pageable);

    /**
     * Find global errors with site name for account (single query, no N+1).
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of global error projections with site name
     */
    @Query("SELECT e.id AS id, e.siteId AS siteId, s.domain AS siteName, " +
            "e.type AS type, e.title AS title, e.severity AS severity, " +
            "e.isRead AS isRead, e.occurredAt AS occurredAt " +
            "FROM ErrorLog e " +
            "JOIN com.bitbi.dfm.site.domain.Site s ON e.siteId = s.id " +
            "WHERE s.accountId = :accountId AND e.batchId IS NULL")
    Page<GlobalErrorProjection> findGlobalErrorsWithSiteByAccountId(UUID accountId, Pageable pageable);

    /**
     * Find unread global errors with site name for account (single query, no N+1).
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of unread global error projections with site name
     */
    @Query("SELECT e.id AS id, e.siteId AS siteId, s.domain AS siteName, " +
            "e.type AS type, e.title AS title, e.severity AS severity, " +
            "e.isRead AS isRead, e.occurredAt AS occurredAt " +
            "FROM ErrorLog e " +
            "JOIN com.bitbi.dfm.site.domain.Site s ON e.siteId = s.id " +
            "WHERE s.accountId = :accountId AND e.batchId IS NULL AND e.isRead = false")
    Page<GlobalErrorProjection> findGlobalErrorsWithSiteByAccountIdAndUnread(UUID accountId, Pageable pageable);

    /**
     * Count unread global errors for account.
     * <p>
     * Uses partial index idx_error_logs_global_unread for performance.
     * </p>
     *
     * @param accountId account identifier
     * @return count of unread global errors
     */
    @Query("SELECT COUNT(e) FROM ErrorLog e " +
            "JOIN com.bitbi.dfm.site.domain.Site s ON e.siteId = s.id " +
            "WHERE s.accountId = :accountId AND e.batchId IS NULL AND e.isRead = false")
    long countUnreadGlobalErrorsByAccountId(UUID accountId);

    /**
     * Mark specified errors as read (internal use only - no account filtering).
     *
     * @param ids list of error IDs to mark as read
     * @return number of errors actually marked as read
     * @deprecated Use {@link #markAsReadByIdsAndAccountId(List, UUID)} for secure bulk updates.
     *             This method will be removed in a future release.
     */
    @Deprecated(forRemoval = true)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ErrorLog e SET e.isRead = true WHERE e.id IN :ids AND e.isRead = false")
    int markAsReadByIds(List<UUID> ids);

    /**
     * Mark specified global errors as read with account authorization.
     * <p>
     * Only updates errors that belong to the account's sites and have batch_id IS NULL.
     * This prevents cross-tenant information disclosure.
     * </p>
     *
     * @param ids       list of error IDs to mark as read
     * @param accountId account identifier for authorization
     * @return number of errors actually marked as read
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ErrorLog e SET e.isRead = true " +
            "WHERE e.id IN :ids AND e.isRead = false AND e.batchId IS NULL " +
            "AND e.siteId IN (SELECT s.id FROM com.bitbi.dfm.site.domain.Site s WHERE s.accountId = :accountId)")
    int markAsReadByIdsAndAccountId(List<UUID> ids, UUID accountId);

    /**
     * Mark all unread global errors as read for account.
     * <p>
     * Only affects global errors (batch_id IS NULL) that are unread.
     * </p>
     *
     * @param accountId account identifier
     * @return number of errors marked as read
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ErrorLog e SET e.isRead = true " +
            "WHERE e.siteId IN (SELECT s.id FROM com.bitbi.dfm.site.domain.Site s WHERE s.accountId = :accountId) " +
            "AND e.batchId IS NULL AND e.isRead = false")
    int markAllAsReadByAccountId(UUID accountId);

    /**
     * Find a single global error by ID with account authorization check.
     *
     * @param errorId   error identifier
     * @param accountId account identifier for authorization
     * @return optional error log if found and belongs to account
     */
    @Query("SELECT e FROM ErrorLog e " +
            "JOIN com.bitbi.dfm.site.domain.Site s ON e.siteId = s.id " +
            "WHERE e.id = :errorId AND s.accountId = :accountId AND e.batchId IS NULL")
    Optional<ErrorLog> findGlobalErrorByIdAndAccountId(UUID errorId, UUID accountId);
}
