package com.bitbi.dfm.error.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ErrorLoggingService(ErrorLogRepository errorLogRepository, BatchLifecycleService batchLifecycleService) {
        this.errorLogRepository = errorLogRepository;
        this.batchLifecycleService = batchLifecycleService;
    }

    /**
     * Log error for batch.
     * <p>
     * Updates batch hasErrors flag automatically.
     * </p>
     *
     * @param batchId   batch identifier
     * @param siteId    site identifier
     * @param type      error type (e.g., "validation", "upload", "processing")
     * @param message   error message
     * @param metadata  optional error metadata (stored as JSONB)
     * @return created error log
     */
    public ErrorLog logError(UUID batchId, UUID siteId, String type, String message, Map<String, Object> metadata) {
        logger.debug("Logging error: batchId={}, siteId={}, type={}, message={}",
                    batchId, siteId, type, message);

        ErrorLog errorLog = ErrorLog.create(siteId, batchId, type, type, message, null, null, metadata);
        ErrorLog saved = errorLogRepository.save(errorLog);

        // Update batch hasErrors flag
        batchLifecycleService.markBatchHasErrors(batchId);

        logger.info("Error logged successfully: errorId={}, batchId={}, type={}",
                   saved.getId(), batchId, type);

        return saved;
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
        return logError(batchId, siteId, type, message, null);
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
     * Exception thrown when error log is not found.
     */
    public static class ErrorLogNotFoundException extends RuntimeException {
        public ErrorLogNotFoundException(String message) {
            super(message);
        }
    }
}
