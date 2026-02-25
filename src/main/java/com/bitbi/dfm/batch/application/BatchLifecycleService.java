package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.account.application.AccountProperties;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.batch.domain.BatchType;
import com.bitbi.dfm.batch.presentation.dto.BatchStartRequestDto;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import com.bitbi.dfm.shared.domain.events.BatchStartedEvent;
import com.bitbi.dfm.shared.exception.HeartbeatRequiredException;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
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
    private final LocalDate dbfGracePeriodEnd;

    public BatchLifecycleService(
            BatchRepository batchRepository,
            ApplicationEventPublisher eventPublisher,
            AccountProperties accountProperties,
            SiteRepository siteRepository,
            SiteSchemaService siteSchemaService,
            @Value("${heartbeat.required-interval-minutes:5}") int heartbeatRequiredIntervalMinutes,
            @Value("${schema.enforcement.dbf-grace-period-end:2026-12-31}") String dbfGracePeriodEndStr) {
        this.batchRepository = batchRepository;
        this.eventPublisher = eventPublisher;
        this.accountProperties = accountProperties;
        this.siteRepository = siteRepository;
        this.siteSchemaService = siteSchemaService;
        this.heartbeatRequiredIntervalMinutes = heartbeatRequiredIntervalMinutes;
        this.dbfGracePeriodEnd = LocalDate.parse(dbfGracePeriodEndStr);
    }

    /**
     * Start new batch for site with batch type information.
     * <p>
     * Business rules:
     * - Site must be active (isActive=true)
     * - Heartbeat must have been called recently
     * - Schema required for CDC sites; for DBF sites, enforced after grace period
     * - Only one active batch per site
     * - Maximum concurrent batches per account (configurable in application.yml)
     * - Pins schema version from current site schema at batch start
     * - Clears forceFullUpload directive on batch start
     * </p>
     *
     * @param accountId account identifier
     * @param siteId    site identifier
     * @param request   batch start request with batchType and optional fields
     * @return started batch
     * @throws SiteInactiveException if site is deactivated
     * @throws ActiveBatchExistsException   if site has active batch
     * @throws ConcurrentBatchLimitException if account exceeded concurrent batch limit
     * @throws SchemaRequiredException if schema is missing and required
     */
    public Batch startBatch(UUID accountId, UUID siteId, BatchStartRequestDto request) {
        logger.info("Starting new batch: accountId={}, siteId={}, batchType={}", accountId, siteId, request.batchType());

        // Validate site is active
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + siteId));

        if (!site.getIsActive()) {
            logger.warn("Attempted to start batch for inactive site: siteId={}", siteId);
            throw new SiteInactiveException("Cannot start batch for inactive site. Please activate the site first.");
        }

        // Validate heartbeat was called recently (Feature 021 - US2)
        validateHeartbeat(site);

        // Schema enforcement (Feature 021 - US8 + US7)
        validateSchemaRequirement(site, siteId);

        // Enforce one active batch per site
        if (batchRepository.findActiveBySiteId(siteId).isPresent()) {
            throw new ActiveBatchExistsException("Site already has an active batch: " + siteId);
        }

        // Enforce concurrent batch limit per account with pessimistic lock
        int maxConcurrentBatches = accountProperties.getMaxConcurrentBatches();
        int activeBatchCount = batchRepository.countActiveBatchesByAccountIdWithLock(accountId);
        if (activeBatchCount >= maxConcurrentBatches) {
            throw new ConcurrentBatchLimitException(
                    "Account exceeded concurrent batch limit: " + activeBatchCount + "/" + maxConcurrentBatches);
        }

        // Pin schema version from current site schema
        Integer schemaVersion = siteSchemaService.findSchema(siteId)
                .map(SiteSchema::getSchemaVersion)
                .orElse(null);

        // Create batch with type information
        Batch batch = Batch.start(
                accountId,
                siteId,
                request.batchType(),
                schemaVersion,
                request.expectedFileCount(),
                request.description()
        );
        Batch saved = batchRepository.save(batch);

        // Clear forceFullUpload directive on batch start (Feature 021 - US8)
        if (site.getForceFullUpload()) {
            logger.info("Clearing forceFullUpload directive on batch start: siteId={}", siteId);
            site.clearForceFullUpload();
            siteRepository.save(site);
        }

        // Publish domain event
        BatchStartedEvent event = new BatchStartedEvent(saved.getId(), siteId, accountId);
        eventPublisher.publishEvent(event);

        logger.info("Batch started successfully: batchId={}, batchType={}, schemaVersion={}, s3Path={}",
                saved.getId(), saved.getBatchType(), saved.getSchemaVersion(), saved.getS3Path());
        return saved;
    }

    /**
     * Start new batch for site (legacy overload without request DTO).
     * <p>
     * Retained for backward compatibility with internal callers.
     * Creates a batch without type information.
     * </p>
     *
     * @param accountId account identifier
     * @param siteId    site identifier
     * @return started batch
     * @deprecated Use {@link #startBatch(UUID, UUID, BatchStartRequestDto)} instead
     */
    @Deprecated
    public Batch startBatch(UUID accountId, UUID siteId) {
        logger.info("Starting new batch (legacy): accountId={}, siteId={}", accountId, siteId);

        // Validate site is active
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + siteId));

        if (!site.getIsActive()) {
            logger.warn("Attempted to start batch for inactive site: siteId={}", siteId);
            throw new SiteInactiveException("Cannot start batch for inactive site. Please activate the site first.");
        }

        // Validate heartbeat was called recently (Feature 021 - US2)
        validateHeartbeat(site);

        // Schema enforcement
        validateSchemaRequirement(site, siteId);

        // Enforce one active batch per site
        if (batchRepository.findActiveBySiteId(siteId).isPresent()) {
            throw new ActiveBatchExistsException("Site already has an active batch: " + siteId);
        }

        // Enforce concurrent batch limit per account with pessimistic lock
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
     * Validate schema requirement based on site type.
     * <p>
     * Rules:
     * <ul>
     *   <li>CDC sites (POSTGRES_CDC, MSSQL_CDC, DBF_CDC): schema always required</li>
     *   <li>DBF sites: schema required after grace period; warning logged during grace period</li>
     * </ul>
     * </p>
     *
     * @param site   the site to validate
     * @param siteId site identifier
     * @throws SchemaRequiredException if schema is missing and required
     */
    private void validateSchemaRequirement(Site site, UUID siteId) {
        SiteType siteType = site.getSiteType();
        boolean hasSchema = siteSchemaService.hasSchema(siteId);

        if (siteType.isCdc() && !hasSchema) {
            logger.warn("Attempted to start batch for CDC site without schema: siteId={}, siteType={}", siteId, siteType);
            throw new SchemaRequiredException(
                    "Schema required for " + siteType + " sites. Submit via POST /api/dfc/schema first.");
        }

        if (siteType == SiteType.DBF && !hasSchema) {
            if (LocalDate.now().isAfter(dbfGracePeriodEnd)) {
                logger.warn("DBF schema grace period expired. Schema required: siteId={}, gracePeriodEnd={}", siteId, dbfGracePeriodEnd);
                throw new SchemaRequiredException(
                        "Schema required for DBF sites. Grace period ended on " + dbfGracePeriodEnd + ". Submit via POST /api/dfc/schema first.");
            }
            logger.warn("DBF site without schema (grace period active until {}): siteId={}", dbfGracePeriodEnd, siteId);
        }
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
