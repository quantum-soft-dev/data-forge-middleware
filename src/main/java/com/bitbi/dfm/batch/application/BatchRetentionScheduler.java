package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupRequest;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to cleanup expired batches based on retention policies.
 */
@Component
public class BatchRetentionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BatchRetentionScheduler.class);

    private final BatchRetentionService batchRetentionService;
    private final int cleanupLimit;

    public BatchRetentionScheduler(
            BatchRetentionService batchRetentionService,
            @Value("${batch.retention.cleanup-limit:1000}") int cleanupLimit) {
        this.batchRetentionService = batchRetentionService;
        this.cleanupLimit = cleanupLimit;
    }

    /**
     * Run retention cleanup.
     * <p>
     * Default schedule: daily at 02:00.
     * Override with batch.retention.cron.
     * </p>
     */
    @Scheduled(cron = "${batch.retention.cron:0 0 2 * * *}")
    public void runRetentionCleanup() {
        logger.info("Starting retention cleanup job (limit={})", cleanupLimit);

        try {
            BatchCleanupSummary summary = batchRetentionService.runCleanup(
                    new BatchCleanupRequest(null, null, null, null, cleanupLimit, false)
            );

            logger.info("Retention cleanup completed: candidates={}, deletedBatches={}, deletedFiles={}, deletedBytes={}, errors={}",
                    summary.candidates(), summary.deletedBatches(), summary.deletedFiles(), summary.deletedBytes(), summary.errors().size());
        } catch (Exception e) {
            logger.error("Retention cleanup job failed: {}", e.getMessage(), e);
        }
    }
}
