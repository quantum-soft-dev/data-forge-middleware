package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.account.application.AccountProperties;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import com.bitbi.dfm.shared.domain.events.BatchStartedEvent;
import com.bitbi.dfm.shared.exception.HeartbeatRequiredException;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final BatchRepository batchRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountProperties accountProperties;
    private final SiteRepository siteRepository;
    private final SiteSchemaService siteSchemaService;
    private final int heartbeatRequiredIntervalMinutes;

    public BatchLifecycleService(
            BatchRepository batchRepository,
            ApplicationEventPublisher eventPublisher,
            AccountProperties accountProperties,
            SiteRepository siteRepository,
            SiteSchemaService siteSchemaService,
            @Value("${heartbeat.required-interval-minutes:5}") int heartbeatRequiredIntervalMinutes) {
        this.batchRepository = batchRepository;
        this.eventPublisher = eventPublisher;
        this.accountProperties = accountProperties;
        this.siteRepository = siteRepository;
        this.siteSchemaService = siteSchemaService;
        this.heartbeatRequiredIntervalMinutes = heartbeatRequiredIntervalMinutes;
    }

    /**
     * Start new batch for site.
     * <p>
     * Business rules:
     * - Site must be active (isActive=true)
     * - Only one active batch per site
     * - Maximum concurrent batches per account (configurable in application.yml)
     * </p>
     *
     * @param accountId account identifier
     * @param siteId    site identifier
     * @param domain    site domain
     * @return started batch
     * @throws SiteInactiveException if site is deactivated
     * @throws ActiveBatchExistsException   if site has active batch
     * @throws ConcurrentBatchLimitException if account exceeded concurrent batch limit
     */
    public Batch startBatch(UUID accountId, UUID siteId) {
        logger.info("Starting new batch: accountId={}, siteId={}", accountId, siteId);

        // Validate site is active
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + siteId));

        if (!site.getIsActive()) {
            logger.warn("Attempted to start batch for inactive site: siteId={}", siteId);
            throw new SiteInactiveException("Cannot start batch for inactive site. Please activate the site first.");
        }

        // Validate heartbeat was called recently (Feature 021 - US2)
        validateHeartbeat(site);

        // For POSTGRES_CDC sites, schema must exist before first batch
        if (site.getSiteType() == SiteType.POSTGRES_CDC && !siteSchemaService.hasSchema(siteId)) {
            logger.warn("Attempted to start batch for CDC site without schema: siteId={}", siteId);
            throw new SchemaRequiredException(
                    "Schema required for POSTGRES_CDC sites. Submit via POST /api/dfc/schema first.");
        }

        // Enforce one active batch per site
        if (batchRepository.findActiveBySiteId(siteId).isPresent()) {
            throw new ActiveBatchExistsException("Site already has an active batch: " + siteId);
        }

        // Enforce concurrent batch limit per account with pessimistic lock
        // This prevents race conditions by locking the count query
        int maxConcurrentBatches = accountProperties.getMaxConcurrentBatches();
        int activeBatchCount = batchRepository.countActiveBatchesByAccountIdWithLock(accountId);
        if (activeBatchCount >= maxConcurrentBatches) {
            throw new ConcurrentBatchLimitException(
                    "Account exceeded concurrent batch limit: " + activeBatchCount + "/" + maxConcurrentBatches);
        }

        Batch batch = Batch.start(accountId, siteId);
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

        // Publish domain event with accountId for plugin dispatch
        BatchCompletedEvent event = new BatchCompletedEvent(
                batchId,
                batch.getAccountId(),
                batch.getUploadedFilesCount(),
                batch.getTotalSize()
        );
        eventPublisher.publishEvent(event);

        logger.info("Batch completed successfully: batchId={}", batchId);
        return saved;
    }

    /**
     * Complete batch with warnings.
     * <p>
     * Marks batch as completed but with non-critical warnings.
     * Validates batch is IN_PROGRESS before completion.
     * </p>
     *
     * @param batchId batch identifier
     * @return completed batch with warnings
     * @throws BatchNotFoundException         if batch not found
     * @throws InvalidBatchStatusException if batch is not IN_PROGRESS
     */
    public Batch completeBatchWithWarnings(UUID batchId) {
        logger.info("Completing batch with warnings: batchId={}", batchId);

        Batch batch = getBatch(batchId);
        validateStatus(batch, BatchStatus.IN_PROGRESS, "complete with warnings");

        batch.completeWithWarnings();
        Batch saved = batchRepository.save(batch);

        // Publish domain event with accountId for plugin dispatch (same as regular complete)
        BatchCompletedEvent event = new BatchCompletedEvent(
                batchId,
                batch.getAccountId(),
                batch.getUploadedFilesCount(),
                batch.getTotalSize()
        );
        eventPublisher.publishEvent(event);

        logger.info("Batch completed with warnings: batchId={}", batchId);
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

        // Publish domain event with accountId for plugin dispatch
        BatchExpiredEvent event = new BatchExpiredEvent(batchId, batch.getAccountId());
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
     * Validate that the site has a recent heartbeat within the configured interval.
     * <p>
     * Throws HeartbeatRequiredException (428) if no heartbeat has been recorded
     * or if the last heartbeat is older than the required interval.
     * </p>
     *
     * @param site the site to validate
     * @throws HeartbeatRequiredException if heartbeat is missing or expired
     */
    private void validateHeartbeat(Site site) {
        LocalDateTime lastHeartbeat = site.getLastHeartbeatAt();

        if (lastHeartbeat == null) {
            logger.warn("No heartbeat recorded for site: siteId={}", site.getId());
            throw new HeartbeatRequiredException(site.getId());
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(heartbeatRequiredIntervalMinutes);
        if (lastHeartbeat.isBefore(cutoff)) {
            logger.warn("Heartbeat expired for site: siteId={}, lastHeartbeat={}, cutoff={}",
                    site.getId(), lastHeartbeat, cutoff);
            throw new HeartbeatRequiredException(site.getId());
        }
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
     * Exception thrown when attempting to start batch for inactive site.
     */
    public static class SiteInactiveException extends RuntimeException {
        public SiteInactiveException(String message) {
            super(message);
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

    /**
     * Exception thrown when a CDC site attempts to start a batch without a registered schema.
     */
    public static class SchemaRequiredException extends RuntimeException {
        public SchemaRequiredException(String message) {
            super(message);
        }
    }
}
