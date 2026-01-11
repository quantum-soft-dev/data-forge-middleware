package com.bitbi.dfm.error.application;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.error.domain.GlobalErrorProjection;
import com.bitbi.dfm.error.presentation.dto.GlobalErrorResponseDto;
import com.bitbi.dfm.error.presentation.dto.GlobalErrorSummaryDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Application service for global error handling.
 * <p>
 * Provides operations for viewing and managing global errors (batch_id IS NULL)
 * on the user Dashboard.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional // Default: all methods are transactional; read methods override with readOnly=true
public class GlobalErrorService {

    private static final Logger logger = LoggerFactory.getLogger(GlobalErrorService.class);

    private final ErrorLogRepository errorLogRepository;
    private final SiteRepository siteRepository;

    public GlobalErrorService(ErrorLogRepository errorLogRepository, SiteRepository siteRepository) {
        this.errorLogRepository = errorLogRepository;
        this.siteRepository = siteRepository;
    }

    /**
     * List global errors for account with pagination.
     * <p>
     * Uses projection query to fetch errors with site names in a single query,
     * avoiding N+1 query problem.
     * </p>
     *
     * @param accountId  account identifier
     * @param page       page number (0-indexed)
     * @param size       page size
     * @param unreadOnly if true, only return unread errors
     * @return paginated list of global error summaries
     */
    @Transactional(readOnly = true)
    public PageResponseDto<GlobalErrorSummaryDto> listGlobalErrors(UUID accountId, int page, int size, boolean unreadOnly) {
        logger.debug("Listing global errors: accountId={}, page={}, size={}, unreadOnly={}",
                accountId, page, size, unreadOnly);

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));

        // Single query with projection - includes site name, no N+1
        Page<GlobalErrorProjection> projectionPage;
        if (unreadOnly) {
            projectionPage = errorLogRepository.findGlobalErrorsWithSiteByAccountIdAndUnread(accountId, pageable);
        } else {
            projectionPage = errorLogRepository.findGlobalErrorsWithSiteByAccountId(accountId, pageable);
        }

        List<GlobalErrorSummaryDto> summaries = projectionPage.getContent().stream()
                .map(this::toSummaryDto)
                .toList();

        logger.debug("Retrieved {} global errors for account: accountId={}, page={}/{}, totalElements={}",
                summaries.size(), accountId, page, projectionPage.getTotalPages(), projectionPage.getTotalElements());

        return new PageResponseDto<>(
                summaries,
                projectionPage.getNumber(),
                projectionPage.getSize(),
                projectionPage.getTotalElements(),
                projectionPage.getTotalPages()
        );
    }

    /**
     * Convert projection to summary DTO.
     */
    private GlobalErrorSummaryDto toSummaryDto(GlobalErrorProjection projection) {
        return new GlobalErrorSummaryDto(
                projection.getId(),
                projection.getSiteId(),
                projection.getSiteName(),
                projection.getType(),
                projection.getTitle(),
                projection.getSeverity(),
                projection.getIsRead(),
                projection.getOccurredAt().toInstant(ZoneOffset.UTC)
        );
    }

    /**
     * Get single global error details.
     *
     * @param errorId   error identifier
     * @param accountId account identifier for authorization
     * @return global error details
     * @throws GlobalErrorNotFoundException if error not found or doesn't belong to account
     */
    @Transactional(readOnly = true)
    public GlobalErrorResponseDto getGlobalError(UUID errorId, UUID accountId) {
        logger.debug("Getting global error: errorId={}, accountId={}", errorId, accountId);

        ErrorLog errorLog = errorLogRepository.findGlobalErrorByIdAndAccountId(errorId, accountId)
                .orElseThrow(() -> new GlobalErrorNotFoundException(
                        "Global error not found or access denied: " + errorId));

        // Site may be null if deleted after error was logged; DTO handles gracefully
        Site site = siteRepository.findById(errorLog.getSiteId()).orElse(null);

        return GlobalErrorResponseDto.fromEntity(errorLog, site);
    }

    /**
     * Mark single error as read.
     *
     * @param errorId   error identifier
     * @param accountId account identifier for authorization
     * @throws GlobalErrorNotFoundException if error not found or doesn't belong to account
     */
    public void markAsRead(UUID errorId, UUID accountId) {
        logger.debug("Marking error as read: errorId={}, accountId={}", errorId, accountId);

        ErrorLog errorLog = errorLogRepository.findGlobalErrorByIdAndAccountId(errorId, accountId)
                .orElseThrow(() -> new GlobalErrorNotFoundException(
                        "Global error not found or access denied: " + errorId));

        if (!errorLog.isRead()) {
            errorLog.setRead(true);
            errorLogRepository.save(errorLog);
            logger.info("Error marked as read: errorId={}", errorId);
        }
    }

    /**
     * Mark multiple errors as read with account authorization.
     * <p>
     * Only marks errors that belong to the account's sites and are global errors (batch_id IS NULL).
     * This prevents cross-tenant information disclosure.
     * </p>
     *
     * @param errorIds  list of error IDs to mark as read
     * @param accountId account identifier for authorization
     * @return number of errors actually marked as read
     */
    public int markMultipleAsRead(List<UUID> errorIds, UUID accountId) {
        logger.debug("Marking multiple errors as read: count={}, accountId={}", errorIds.size(), accountId);

        int updated = errorLogRepository.markAsReadByIdsAndAccountId(errorIds, accountId);

        logger.debug("Marked {} errors as read: accountId={}", updated, accountId);
        return updated;
    }

    /**
     * Mark all unread global errors as read for account.
     *
     * @param accountId account identifier
     * @return number of errors marked as read
     */
    public int markAllAsRead(UUID accountId) {
        logger.debug("Marking all global errors as read: accountId={}", accountId);

        int updated = errorLogRepository.markAllAsReadByAccountId(accountId);

        logger.info("Marked all {} global errors as read: accountId={}", updated, accountId);
        return updated;
    }

    /**
     * Get unread global errors count for account.
     *
     * @param accountId account identifier
     * @return unread count
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID accountId) {
        logger.debug("Getting unread count: accountId={}", accountId);

        long count = errorLogRepository.countUnreadGlobalErrorsByAccountId(accountId);

        logger.debug("Unread count for account {}: {}", accountId, count);
        return count;
    }

    /**
     * Exception thrown when global error is not found or access is denied.
     */
    public static class GlobalErrorNotFoundException extends RuntimeException {
        public GlobalErrorNotFoundException(String message) {
            super(message);
        }
    }
}
