package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupRequest;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Scheduled task to cleanup expired batches based on retention policies.
 */
@Component
public class BatchRetentionScheduler implements SchedulingConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(BatchRetentionScheduler.class);
    private static final String DEFAULT_FALLBACK_CRON = "0 0 2 * * *";

    private final BatchRetentionService batchRetentionService;
    private final BatchRetentionScheduleService scheduleService;
    private final int cleanupLimit;

    public BatchRetentionScheduler(
            BatchRetentionService batchRetentionService,
            BatchRetentionScheduleService scheduleService,
            @Value("${batch.retention.cleanup-limit:1000}") int cleanupLimit) {
        this.batchRetentionService = batchRetentionService;
        this.scheduleService = scheduleService;
        this.cleanupLimit = cleanupLimit;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(this::runRetentionCleanup, cronTrigger());
    }

    private Trigger cronTrigger() {
        return triggerContext -> {
            String cron = scheduleService.getEffectiveCron();
            try {
                return new CronTrigger(cron).nextExecution(triggerContext);
            } catch (Exception e) {
                logger.error("Failed to compute next execution time for retention cleanup cron='{}'. Falling back to default cron='{}'.",
                        cron, DEFAULT_FALLBACK_CRON, e);
                return new CronTrigger(DEFAULT_FALLBACK_CRON).nextExecution(triggerContext);
            }
        };
    }

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
