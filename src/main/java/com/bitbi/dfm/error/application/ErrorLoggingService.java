package com.bitbi.dfm.error.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.error.domain.ErrorSeverity;
import com.bitbi.dfm.error.presentation.dto.ErrorLogSummaryDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for error logging operations.
 * <p>
 * Handles error log creation and updates batch hasErrors flag.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class ErrorLoggingService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLoggingService.class);

    private final ErrorLogRepository errorLogRepository;
    private final BatchLifecycleService batchLifecycleService;
    private final BatchRepository batchRepository;

    public ErrorLoggingService(ErrorLogRepository errorLogRepository,
                               BatchLifecycleService batchLifecycleService,
                               BatchRepository batchRepository) {
        this.errorLogRepository = errorLogRepository;
        this.batchLifecycleService = batchLifecycleService;
        this.batchRepository = batchRepository;
    }

    /**
     * Log error for batch with severity.
     * <p>
     * Updates batch hasErrors flag automatically.
     * </p>
     *
     * @param batchId  batch identifier
     * @param siteId   site identifier
     * @param type     error type (e.g., "validation", "upload", "processing")
     * @param message  error message
     * @param metadata optional error metadata (stored as JSONB)
     * @param severity error severity level (defaults to ERROR if null)
     * @return created error log
     */
    public ErrorLog logError(UUID batchId, UUID siteId, String type, String message,
                             Map<String, Object> metadata, ErrorSeverity severity) {
        logger.debug("Logging error: batchId={}, siteId={}, type={}, severity={}, message={}",
                batchId, siteId, type, severity, message);

        ErrorLog errorLog = ErrorLog.create(siteId, batchId, type, type, message, null, null, metadata, severity);
        ErrorLog saved = errorLogRepository.save(errorLog);

        // Update batch hasErrors flag
        batchLifecycleService.markBatchHasErrors(batchId);

        logger.info("Error logged successfully: errorId={}, batchId={}, type={}, severity={}",
                saved.getId(), batchId, type, saved.getSeverity());

        return saved;
    }

    /**
     * Log error for batch with default severity (ERROR).
     * <p>
     * Updates batch hasErrors flag automatically.
     * </p>
     *
     * @param batchId  batch identifier
     * @param siteId   site identifier
     * @param type     error type (e.g., "validation", "upload", "processing")
     * @param message  error message
     * @param metadata optional error metadata (stored as JSONB)
     * @return created error log
     */
    public ErrorLog logError(UUID batchId, UUID siteId, String type, String message, Map<String, Object> metadata) {
        return logError(batchId, siteId, type, message, metadata, ErrorSeverity.ERROR);
    }

    /**
     * Log error without metadata.
     *
     * @param batchId batch identifier
     * @param siteId  site identifier
     * @param type    error type
     * @param message error message
     * @return created error log
     */
    public ErrorLog logError(UUID batchId, UUID siteId, String type, String message) {
        return logError(batchId, siteId, type, message, null, ErrorSeverity.ERROR);
    }

    /**
     * Log standalone error without batch association with severity.
     * <p>
     * Used for errors that occur outside of batch processing context,
     * such as configuration errors, startup errors, or authentication errors.
     * </p>
     *
     * @param siteId   site identifier
     * @param type     error type (e.g., "ConfigurationError", "AuthenticationError")
     * @param message  error message
     * @param metadata optional error metadata (stored as JSONB)
     * @param severity error severity level (defaults to ERROR if null)
     * @return created error log
     */
    public ErrorLog logStandaloneError(UUID siteId, String type, String message,
                                       Map<String, Object> metadata, ErrorSeverity severity) {
        logger.debug("Logging standalone error: siteId={}, type={}, severity={}, message={}",
                siteId, type, severity, message);

        ErrorLog errorLog = ErrorLog.create(siteId, null, type, type, message, null, null, metadata, severity);
        ErrorLog saved = errorLogRepository.save(errorLog);

        logger.info("Standalone error logged successfully: errorId={}, siteId={}, type={}, severity={}",
                saved.getId(), siteId, type, saved.getSeverity());

        return saved;
    }

    /**
     * Log standalone error without batch association with default severity (ERROR).
     * <p>
     * Used for errors that occur outside of batch processing context,
     * such as configuration errors, startup errors, or authentication errors.
     * </p>
     *
     * @param siteId   site identifier
     * @param type     error type (e.g., "ConfigurationError", "AuthenticationError")
     * @param message  error message
     * @param metadata optional error metadata (stored as JSONB)
     * @return created error log
     */
    public ErrorLog logStandaloneError(UUID siteId, String type, String message, Map<String, Object> metadata) {
        return logStandaloneError(siteId, type, message, metadata, ErrorSeverity.ERROR);
    }

    /**
     * Log standalone error without metadata.
     *
     * @param siteId  site identifier
     * @param type    error type
     * @param message error message
     * @return created error log
     */
    public ErrorLog logStandaloneError(UUID siteId, String type, String message) {
        return logStandaloneError(siteId, type, message, null, ErrorSeverity.ERROR);
    }

    /**
     * Get error log by ID.
     *
     * @param errorId error identifier
     * @return error log
     * @throws ErrorLogNotFoundException if error log not found
     */
    @Transactional(readOnly = true)
    public ErrorLog getErrorLog(UUID errorId) {
        return errorLogRepository.findById(errorId)
                .orElseThrow(() -> new ErrorLogNotFoundException("Error log not found: " + errorId));
    }

    /**
     * List all errors for batch.
     *
     * @param batchId batch identifier
     * @return list of error logs
     */
    @Transactional(readOnly = true)
    public List<ErrorLog> listErrorsByBatch(UUID batchId) {
        return errorLogRepository.findByBatchId(batchId);
    }

    /**
     * List errors by site.
     *
     * @param siteId site identifier
     * @return list of error logs
     */
    @Transactional(readOnly = true)
    public List<ErrorLog> listErrorsBySite(UUID siteId) {
        return errorLogRepository.findBySiteId(siteId);
    }

    /**
     * Get error count for batch.
     *
     * @param batchId batch identifier
     * @return number of errors
     */
    @Transactional(readOnly = true)
    public long countErrorsByBatch(UUID batchId) {
        return errorLogRepository.countByBatchId(batchId);
    }

    /**
     * Get paginated errors for batch (Phase 7 - Error Details View).
     * <p>
     * Authorization: Verifies batch belongs to user's account before returning errors.
     * Returns errors sorted by occurredAt DESC (newest first).
     * </p>
     *
     * @param batchId   batch identifier
     * @param accountId account identifier (from JWT token)
     * @param page      page number (0-indexed)
     * @param size      page size (default 20)
     * @return paginated error list
     * @throws UnauthorizedBatchAccessException if batch doesn't belong to account
     * @throws BatchNotFoundException           if batch not found
     */
    @Transactional(readOnly = true)
    public PageResponseDto<ErrorLogSummaryDto> getBatchErrors(UUID batchId, UUID accountId, int page, int size) {
        logger.debug("Getting errors for batch: batchId={}, accountId={}, page={}, size={}",
                    batchId, accountId, page, size);

        // Authorization check: Verify batch belongs to user's account
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BatchNotFoundException("Batch not found: " + batchId));

        if (!batch.getAccountId().equals(accountId)) {
            logger.warn("Unauthorized access attempt: accountId={} tried to access batch={} owned by account={}",
                       accountId, batchId, batch.getAccountId());
            throw new UnauthorizedBatchAccessException("Batch does not belong to user account");
        }

        // Create pageable with sort by occurredAt DESC (newest errors first)
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));

        // Query errors with pagination
        Page<ErrorLog> errorPage = errorLogRepository.findByBatchId(batchId, pageable);

        // Convert to DTOs
        List<ErrorLogSummaryDto> errorDtos = errorPage.getContent().stream()
                .map(ErrorLogSummaryDto::fromEntity)
                .toList();

        logger.info("Retrieved {} errors for batch: batchId={}, page={}/{}, totalElements={}",
                   errorDtos.size(), batchId, page, errorPage.getTotalPages(), errorPage.getTotalElements());

        return new PageResponseDto<>(
                errorDtos,
                errorPage.getNumber(),
                errorPage.getSize(),
                errorPage.getTotalElements(),
                errorPage.getTotalPages()
        );
    }

    /**
     * Exception thrown when error log is not found.
     */
    public static class ErrorLogNotFoundException extends RuntimeException {
        public ErrorLogNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when batch is not found.
     */
    public static class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when user tries to access batch that doesn't belong to their account.
     */
    public static class UnauthorizedBatchAccessException extends RuntimeException {
        public UnauthorizedBatchAccessException(String message) {
            super(message);
        }
    }
}
