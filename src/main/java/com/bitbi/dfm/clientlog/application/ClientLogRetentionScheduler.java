package com.bitbi.dfm.clientlog.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task for cleaning up expired client diagnostic logs.
 * <p>
 * Runs on a configurable cron schedule (default: 3 AM daily).
 * Deletes expired logs from both S3 and the database.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US5 - Admin Views and Downloads Client Logs
 */
@Component
public class ClientLogRetentionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ClientLogRetentionScheduler.class);

    private final ClientDiagnosticLogService logService;

    public ClientLogRetentionScheduler(ClientDiagnosticLogService logService) {
        this.logService = logService;
    }

    /**
     * Delete expired client diagnostic logs.
     * <p>
     * Configured via client-logs.retention-cron property (default: "0 0 3 * * *" = 3 AM daily).
     * </p>
     */
    @Scheduled(cron = "${client-logs.retention-cron:0 0 3 * * *}")
    public void cleanupExpiredLogs() {
        logger.info("Starting client log retention cleanup");

        try {
            int deleted = logService.deleteExpiredLogs();
            logger.info("Client log retention cleanup complete: deleted {} logs", deleted);
        } catch (Exception e) {
            logger.error("Client log retention cleanup failed: {}", e.getMessage(), e);
        }
    }
}
