package com.bitbi.dfm.error.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for ErrorLog entity.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface ErrorLogRepository {

    ErrorLog save(ErrorLog errorLog);

    Optional<ErrorLog> findById(UUID errorId);

    List<ErrorLog> findByBatchId(UUID batchId);

    Page<ErrorLog> findByBatchId(UUID batchId, Pageable pageable);

    List<ErrorLog> findBySiteId(UUID siteId);

    Page<ErrorLog> findBySiteId(UUID siteId, Pageable pageable);

    Page<ErrorLog> findByTypeAndOccurredAtBetween(String type, LocalDateTime start,
                                                    LocalDateTime end, Pageable pageable);

    List<ErrorLog> exportByFilters(UUID siteId, String type, LocalDateTime start, LocalDateTime end);

    long countByBatchId(UUID batchId);

    long countBySiteId(UUID siteId);

    long countBySiteIds(List<UUID> siteIds);

    long count();

    Page<ErrorLog> findBySiteIdAndType(UUID siteId, String type, Pageable pageable);

    Page<ErrorLog> findByType(String type, Pageable pageable);

    Page<ErrorLog> findAll(Pageable pageable);

    void deleteById(UUID id);

    /**
     * Delete every error log of a site (issue #89 — history wipe).
     *
     * @param siteId site identifier
     * @return number of rows deleted
     */
    int deleteBySiteId(UUID siteId);

    // ==================== Global Error Handling Methods ====================

    /**
     * Find global errors (batch_id IS NULL) for account with pagination.
     * <p>
     * Returns full ErrorLog entities. For list views, prefer
     * {@link #findGlobalErrorsWithSiteByAccountId(UUID, Pageable)} to include site name.
     * </p>
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of global error logs
     */
    Page<ErrorLog> findGlobalErrorsByAccountId(UUID accountId, Pageable pageable);

    /**
     * Find unread global errors (batch_id IS NULL AND is_read = false) for account.
     * <p>
     * Returns full ErrorLog entities. For list views, prefer
     * {@link #findGlobalErrorsWithSiteByAccountIdAndUnread(UUID, Pageable)} to include site name.
     * </p>
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of unread global error logs
     */
    Page<ErrorLog> findGlobalErrorsByAccountIdAndUnread(UUID accountId, Pageable pageable);

    /**
     * Find global errors with site name for account (single query, no N+1).
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of global error projections with site name
     */
    Page<GlobalErrorProjection> findGlobalErrorsWithSiteByAccountId(UUID accountId, Pageable pageable);

    /**
     * Find unread global errors with site name for account (single query, no N+1).
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of unread global error projections with site name
     */
    Page<GlobalErrorProjection> findGlobalErrorsWithSiteByAccountIdAndUnread(UUID accountId, Pageable pageable);

    /**
     * Count unread global errors for account.
     *
     * @param accountId account identifier
     * @return count of unread global errors
     */
    long countUnreadGlobalErrorsByAccountId(UUID accountId);

    /**
     * Mark specified errors as read (internal use only - no account filtering).
     *
     * @param ids list of error IDs to mark as read
     * @return number of errors actually marked as read
     * @deprecated Use {@link #markAsReadByIdsAndAccountId(List, UUID)} for secure bulk updates
     */
    @Deprecated
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
    int markAsReadByIdsAndAccountId(List<UUID> ids, UUID accountId);

    /**
     * Mark all unread global errors as read for account.
     *
     * @param accountId account identifier
     * @return number of errors marked as read
     */
    int markAllAsReadByAccountId(UUID accountId);

    /**
     * Find a single global error by ID with account authorization check.
     *
     * @param errorId   error identifier
     * @param accountId account identifier for authorization
     * @return optional error log if found and belongs to account
     */
    Optional<ErrorLog> findGlobalErrorByIdAndAccountId(UUID errorId, UUID accountId);
}
