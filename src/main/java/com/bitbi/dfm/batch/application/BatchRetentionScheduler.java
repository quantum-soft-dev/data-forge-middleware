package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupRequest;
import com.bitbi.dfm.batch.application.BatchRetentionService.BatchCleanupSummary;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled task to cleanup expired batches based on retention policies.
 *
 * <p>
 * Uses a dynamic cron schedule that can be updated at runtime via admin settings.
 * When the cron is updated, the scheduler is rescheduled without restarting the app.
 * </p>
 */
@Component
public class BatchRetentionScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BatchRetentionScheduler.class);
    private static final String DEFAULT_FALLBACK_CRON = "0 0 2 * * *";

    private final TaskScheduler taskScheduler;
    private final BatchRetentionService batchRetentionService;
    private final BatchRetentionScheduleService scheduleService;
    private final int cleanupLimit;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Object scheduleLock = new Object();
    private volatile ScheduledFuture<?> scheduledFuture;
    private volatile String scheduledCron;

    public BatchRetentionScheduler(
            TaskScheduler taskScheduler,
            BatchRetentionService batchRetentionService,
            BatchRetentionScheduleService scheduleService,
            @Value("${batch.retention.cleanup-limit:1000}") int cleanupLimit) {
        this.taskScheduler = taskScheduler;
        this.batchRetentionService = batchRetentionService;
        this.scheduleService = scheduleService;
        this.cleanupLimit = cleanupLimit;
    }

    @PostConstruct
    public void initialize() {
        reschedule(scheduleService.getEffectiveCron());
    }

    @EventListener
    public void onScheduleChanged(BatchRetentionScheduleChangedEvent event) {
        if (event == null || event.cron() == null || event.cron().isBlank()) {
            return;
        }
        reschedule(event.cron());
    }

    private void reschedule(String cron) {
        String desiredCron = cron != null && !cron.isBlank() ? cron : DEFAULT_FALLBACK_CRON;

        synchronized (scheduleLock) {
            if (desiredCron.equals(scheduledCron) && scheduledFuture != null && !scheduledFuture.isCancelled()) {
                return;
            }

            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }

            try {
                scheduledFuture = taskScheduler.schedule(this::runRetentionCleanup, new CronTrigger(desiredCron));
                scheduledCron = desiredCron;
                logger.info("Retention cleanup scheduler configured: cron='{}', limit={}", scheduledCron, cleanupLimit);
            } catch (TaskRejectedException rejected) {
                // Not the expression's fault: the scheduler is shut down, which since issue #146
                // happens when the context closes rather than when its bean is destroyed. Retrying
                // with the fallback cron would be rejected for the same reason and would blame a
                // second, equally innocent expression in the log. This runs from an @EventListener
                // inside the admin's transaction, so it must not throw — letting it escape would
                // roll back a saved schedule and answer 500 to a request that arrived while the pod
                // was going away. The next instance rearms from the stored setting.
                unschedule();
                logger.warn("Retention cleanup was not rescheduled to cron='{}': the task scheduler is "
                        + "shut down. This instance leaves it unscheduled.", desiredCron, rejected);
            } catch (Exception e) {
                logger.error("Failed to schedule retention cleanup with cron='{}'. Falling back to cron='{}'.",
                        desiredCron, DEFAULT_FALLBACK_CRON, e);
                try {
                    scheduledFuture = taskScheduler.schedule(this::runRetentionCleanup, new CronTrigger(DEFAULT_FALLBACK_CRON));
                    scheduledCron = DEFAULT_FALLBACK_CRON;
                } catch (Exception fallbackFailure) {
                    // The fallback cron is a constant, so nothing about it can be wrong; what is left
                    // is a scheduler that died between the two calls. Same reasoning as above — this
                    // runs inside the admin's transaction and must not throw.
                    unschedule();
                    logger.error("Retention cleanup could not be scheduled at all (cron='{}'); leaving it "
                            + "unscheduled in this instance.", DEFAULT_FALLBACK_CRON, fallbackFailure);
                }
            }
        }
    }

    /** Forget the current schedule; used when nothing could be armed. */
    private void unschedule() {
        scheduledFuture = null;
        scheduledCron = null;
    }

    public void runRetentionCleanup() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("Retention cleanup job is already running; skipping this trigger (cron='{}').", scheduledCron);
            return;
        }

        logger.info("Starting retention cleanup job (limit={}, cron='{}')", cleanupLimit, scheduledCron);

        try {
            BatchCleanupSummary summary = batchRetentionService.runCleanup(
                    new BatchCleanupRequest(null, null, null, null, cleanupLimit, false)
            );

            logger.info("Retention cleanup completed: candidates={}, deletedBatches={}, deletedFiles={}, deletedBytes={}, errors={}",
                    summary.candidates(), summary.deletedBatches(), summary.deletedFiles(), summary.deletedBytes(), summary.errors().size());
        } catch (Exception e) {
            logger.error("Retention cleanup job failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}

