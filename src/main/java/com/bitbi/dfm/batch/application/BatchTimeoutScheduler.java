package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled task to mark expired batches as NOT_COMPLETED.
 * <p>
 * Runs every 5 minutes to check for batches that have exceeded
 * the configured timeout threshold.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class BatchTimeoutScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BatchTimeoutScheduler.class);

    private final BatchRepository batchRepository;
    private final BatchLifecycleService batchLifecycleService;
    private final int timeoutMinutes;

    public BatchTimeoutScheduler(
            BatchRepository batchRepository,
            BatchLifecycleService batchLifecycleService,
            @Value("${batch.timeout-minutes:60}") int timeoutMinutes) {
        this.batchRepository = batchRepository;
        this.batchLifecycleService = batchLifecycleService;
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Check for expired batches and mark them as NOT_COMPLETED.
     * <p>
     * Runs every 5 minutes (300000 milliseconds).
     * Cron expression: 0 *&#47;5 * * * * (every 5 minutes at the top of the 5-minute interval)
     * </p>
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void checkExpiredBatches() {
        logger.debug("Running batch timeout check (timeout: {} minutes)", timeoutMinutes);

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Batch> expiredBatches = batchRepository.findExpiredBatches(cutoffTime);

        if (expiredBatches.isEmpty()) {
            logger.debug("No expired batches found");
            return;
        }

        logger.info("Found {} expired batches to mark as NOT_COMPLETED", expiredBatches.size());

        int successCount = 0;
        int failureCount = 0;

        for (Batch batch : expiredBatches) {
            try {
                batchLifecycleService.markBatchNotCompleted(batch.getId());
                successCount++;
            } catch (OptimisticLockException e) {
                // Another scheduler instance already updated this batch - this is expected
                logger.debug("Batch already updated by another instance: batchId={}", batch.getId());
                successCount++; // Count as success since batch was processed
            } catch (Exception e) {
                logger.error("Failed to mark batch as NOT_COMPLETED: batchId={}, error={}",
                           batch.getId(), e.getMessage(), e);
                failureCount++;
            }
        }

        logger.info("Batch timeout check completed: {} succeeded, {} failed",
                   successCount, failureCount);
    }
}
