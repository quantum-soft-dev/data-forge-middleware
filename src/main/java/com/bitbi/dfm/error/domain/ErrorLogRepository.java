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

    // ==================== Global Error Handling Methods ====================

    /**
     * Find global errors (batch_id IS NULL) for account with pagination.
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of global error logs
     */
    Page<ErrorLog> findGlobalErrorsByAccountId(UUID accountId, Pageable pageable);

    /**
     * Find unread global errors (batch_id IS NULL AND is_read = false) for account.
     *
     * @param accountId account identifier
     * @param pageable  pagination parameters
     * @return page of unread global error logs
     */
    Page<ErrorLog> findGlobalErrorsByAccountIdAndUnread(UUID accountId, Pageable pageable);

    /**
     * Count unread global errors for account.
     *
     * @param accountId account identifier
     * @return count of unread global errors
     */
    long countUnreadGlobalErrorsByAccountId(UUID accountId);

    /**
     * Mark specified errors as read.
     *
     * @param ids        list of error IDs to mark as read
     * @param occurredAt partition key for partition pruning (optional, can be null)
     * @return number of errors actually marked as read
     */
    int markAsReadByIds(List<UUID> ids, java.time.LocalDateTime occurredAt);

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
