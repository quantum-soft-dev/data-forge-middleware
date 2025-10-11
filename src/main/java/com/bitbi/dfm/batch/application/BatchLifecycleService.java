package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import com.bitbi.dfm.shared.domain.events.BatchStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Application service for batch lifecycle management.
 * <p>
 * Handles batch operations: start, complete, fail, cancel.
 * Validates status transitions and enforces business rules.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class BatchLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(BatchLifecycleService.class);
    private static final int MAX_CONCURRENT_BATCHES_PER_ACCOUNT = 5;

    private final BatchRepository batchRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BatchLifecycleService(BatchRepository batchRepository, ApplicationEventPublisher eventPublisher) {
        this.batchRepository = batchRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Start new batch for site.
     * <p>
     * Business rules:
     * - Only one active batch per site
     * - Maximum 5 concurrent batches per account
     * </p>
     *
     * @param accountId account identifier
     * @param siteId    site identifier
     * @param domain    site domain
     * @return started batch
     * @throws ActiveBatchExistsException   if site has active batch
     * @throws ConcurrentBatchLimitException if account exceeded concurrent batch limit
     */
    public Batch startBatch(UUID accountId, UUID siteId, String domain) {
        logger.info("Starting new batch: accountId={}, siteId={}, domain={}", accountId, siteId, domain);

        // Enforce one active batch per site
        if (batchRepository.findActiveBySiteId(siteId).isPresent()) {
            throw new ActiveBatchExistsException("Site already has an active batch: " + siteId);
        }

        // Enforce concurrent batch limit per account with pessimistic lock
        // This prevents race conditions by locking the count query
        int activeBatchCount = batchRepository.countActiveBatchesByAccountIdWithLock(accountId);
        if (activeBatchCount >= MAX_CONCURRENT_BATCHES_PER_ACCOUNT) {
            throw new ConcurrentBatchLimitException(
                    "Account exceeded concurrent batch limit: " + activeBatchCount + "/" + MAX_CONCURRENT_BATCHES_PER_ACCOUNT);
        }

        Batch batch = Batch.start(accountId, siteId, domain);
        Batch saved = batchRepository.save(batch);

        // Publish domain event
        BatchStartedEvent event = new BatchStartedEvent(saved.getId(), siteId, accountId);
        eventPublisher.publishEvent(event);

        logger.info("Batch started successfully: batchId={}, s3Path={}", saved.getId(), saved.getS3Path());
        return saved;
    }

    /**
     * Get batch by ID.
     *
     * @param batchId batch identifier
     * @return batch
     * @throws BatchNotFoundException if batch not found
     */
    @Transactional(readOnly = true)
    public Batch getBatch(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new BatchNotFoundException("Batch not found"));
    }

    /**
     * Complete batch successfully.
     * <p>
     * Validates batch is IN_PROGRESS before completion.
     * </p>
     *
     * @param batchId batch identifier
     * @return completed batch
     * @throws BatchNotFoundException         if batch not found
     * @throws InvalidBatchStatusException if batch is not IN_PROGRESS
     */
    public Batch completeBatch(UUID batchId) {
        logger.info("Completing batch: batchId={}", batchId);

        Batch batch = getBatch(batchId);
        validateStatus(batch, BatchStatus.IN_PROGRESS, "complete");

        batch.complete();
        Batch saved = batchRepository.save(batch);

        // Publish domain event
        BatchCompletedEvent event = new BatchCompletedEvent(batchId, batch.getUploadedFilesCount(), batch.getTotalSize());
        eventPublisher.publishEvent(event);

        logger.info("Batch completed successfully: batchId={}", batchId);
        return saved;
    }

    /**
     * Fail batch with error.
     * <p>
     * Validates batch is IN_PROGRESS before failing.
     * </p>
     *
     * @param batchId batch identifier
     * @return failed batch
     * @throws BatchNotFoundException         if batch not found
     * @throws InvalidBatchStatusException if batch is not IN_PROGRESS
     */
    public Batch failBatch(UUID batchId) {
        logger.info("Failing batch: batchId={}", batchId);

        Batch batch = getBatch(batchId);
        validateStatus(batch, BatchStatus.IN_PROGRESS, "fail");

        batch.fail();
        Batch saved = batchRepository.save(batch);

        logger.info("Batch failed: batchId={}", batchId);
        return saved;
    }

    /**
     * Cancel batch (user-initiated).
     * <p>
     * Validates batch is IN_PROGRESS before cancelling.
     * </p>
     *
     * @param batchId batch identifier
     * @return cancelled batch
     * @throws BatchNotFoundException         if batch not found
     * @throws InvalidBatchStatusException if batch is not IN_PROGRESS
     */
    public Batch cancelBatch(UUID batchId) {
        logger.info("Cancelling batch: batchId={}", batchId);

        Batch batch = getBatch(batchId);
        validateStatus(batch, BatchStatus.IN_PROGRESS, "cancel");

        batch.cancel();
        Batch saved = batchRepository.save(batch);

        logger.info("Batch cancelled: batchId={}", batchId);
        return saved;
    }

    /**
     * Mark batch as NOT_COMPLETED due to timeout.
     * <p>
     * Used by BatchTimeoutScheduler for expired batches.
     * </p>
     *
     * @param batchId batch identifier
     * @return not completed batch
     * @throws BatchNotFoundException if batch not found
     */
    public Batch markBatchNotCompleted(UUID batchId) {
        logger.info("Marking batch as NOT_COMPLETED: batchId={}", batchId);

        Batch batch = getBatch(batchId);

        if (batch.getStatus() != BatchStatus.IN_PROGRESS) {
            logger.warn("Cannot mark non-IN_PROGRESS batch as NOT_COMPLETED: batchId={}, status={}",
                       batchId, batch.getStatus());
            return batch;
        }

        batch.markAsNotCompleted();
        Batch saved = batchRepository.save(batch);

        // Publish domain event
        BatchExpiredEvent event = new BatchExpiredEvent(batchId);
        eventPublisher.publishEvent(event);

        logger.info("Batch marked as NOT_COMPLETED: batchId={}", batchId);
        return saved;
    }

    /**
     * Update batch hasErrors flag.
     * <p>
     * Called by ErrorLoggingService when errors are logged.
     * </p>
     *
     * @param batchId batch identifier
     */
    public void markBatchHasErrors(UUID batchId) {
        logger.debug("Marking batch as having errors: batchId={}", batchId);

        Batch batch = getBatch(batchId);

        if (batch.getHasErrors()) {
            return; // Already marked
        }

        batch.markAsHavingErrors();
        batchRepository.save(batch);
    }

    /**
     * Validate batch status for operation.
     *
     * @param batch            batch
     * @param expectedStatus   expected status
     * @param operation        operation name
     * @throws InvalidBatchStatusException if status is invalid
     */
    private void validateStatus(Batch batch, BatchStatus expectedStatus, String operation) {
        if (batch.getStatus() != expectedStatus) {
            throw new InvalidBatchStatusException(
                    String.format("Cannot %s batch in status %s (expected %s): batchId=%s",
                                operation, batch.getStatus(), expectedStatus, batch.getId()));
        }
    }

    /**
     * Exception thrown when site has active batch.
     */
    public static class ActiveBatchExistsException extends RuntimeException {
        public ActiveBatchExistsException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when account exceeds concurrent batch limit.
     */
    public static class ConcurrentBatchLimitException extends RuntimeException {
        public ConcurrentBatchLimitException(String message) {
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
     * Exception thrown when batch status is invalid for operation.
     */
    public static class InvalidBatchStatusException extends RuntimeException {
        public InvalidBatchStatusException(String message) {
            super(message);
        }
    }
}
