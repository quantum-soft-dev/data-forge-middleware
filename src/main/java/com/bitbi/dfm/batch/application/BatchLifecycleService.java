package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.account.application.AccountProperties;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import com.bitbi.dfm.shared.domain.events.BatchStartedEvent;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    public BatchLifecycleService(
            BatchRepository batchRepository,
            ApplicationEventPublisher eventPublisher,
            AccountProperties accountProperties,
            SiteRepository siteRepository,
            SiteSchemaService siteSchemaService) {
        this.batchRepository = batchRepository;
        this.eventPublisher = eventPublisher;
        this.accountProperties = accountProperties;
        this.siteRepository = siteRepository;
        this.siteSchemaService = siteSchemaService;
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
     * @throws SiteNotFoundException if the site no longer exists
     * @throws SiteInactiveException if site is deactivated
     * @throws SchemaRequiredException if a POSTGRES_CDC site has no schema on file
     * @throws ActiveBatchExistsException   if site has active batch
     * @throws ConcurrentBatchLimitException if account exceeded concurrent batch limit
     */
    public Batch startBatch(UUID accountId, UUID siteId) {
        return startBatch(accountId, siteId, null);
    }

    /**
     * Start a new batch for a Delta v2 session, recording its mode (issue #84) so a FULL_SNAPSHOT
     * can be recognized while it is still uploading. Same business rules as
     * {@link #startBatch(UUID, UUID)}.
     *
     * @param accountId   account identifier
     * @param siteId      site identifier
     * @param sessionMode Delta v2 session mode (FULL_SNAPSHOT, DELTA, CONTINUOUS), may be null
     * @return started batch
     */
    public Batch startBatch(UUID accountId, UUID siteId, String sessionMode) {
        logger.info("Starting new batch: accountId={}, siteId={}, sessionMode={}", accountId, siteId, sessionMode);

        // Validate site is active
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("Site not found: " + siteId));

        if (!site.getIsActive()) {
            logger.warn("Attempted to start batch for inactive site: siteId={}", siteId);
            throw new SiteInactiveException("Cannot start batch for inactive site. Please activate the site first.");
        }

        // For POSTGRES_CDC sites, schema must exist before first batch
        if (site.getSiteType() == SiteType.POSTGRES_CDC && !siteSchemaService.hasSchema(siteId)) {
            logger.warn("Attempted to start batch for CDC site without schema: siteId={}", siteId);
            throw new SchemaRequiredException(
                    "Schema required for POSTGRES_CDC sites. Submit it through Delta gRPC SubmitSchema first.");
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

        Batch batch = Batch.start(accountId, siteId, sessionMode);
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
     * Record live session activity on a streaming batch (029). Called by the Delta v2 ingest path
     * at a bounded cadence (session start/resume, Ack watermark, segment seal) so the timeout
     * sweeper can tell a long-lived live session from a silently abandoned one.
     * <p>
     * 030: a lock-free, single-column update — never a load-modify-save. {@link Batch} is
     * {@code @Version}ed, so the old read-modify-write made this liveness stamp compete for the
     * version with real transitions (the sweeper failing the batch, a segment commit) and the loser
     * threw {@code OptimisticLockingFailureException} into the gRPC ingest path, killing a healthy
     * live session. A stamp is not a state transition and has no business in that protocol.
     * </p>
     * <p>
     * Best-effort on top of that: a missing/terminal batch (0 rows) and any persistence failure are
     * logged and swallowed, because the liveness signal must never fail the ingest stream. Runs
     * with {@code SUPPORTS} so the swallow happens outside the write's own transaction (see
     * {@code JpaBatchRepository#touchActivity}) rather than inside a doomed one.
     * </p>
     *
     * @param batchId batch identifier
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public void touchActivity(UUID batchId) {
        try {
            if (batchRepository.touchActivity(batchId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
                // 031/T10: also the normal outcome for a frame that arrives after the batch ended —
                // debug, not warn, since a late heartbeat on a finished session is expected noise.
                logger.debug("touchActivity: batch missing or no longer IN_PROGRESS, skipping: batchId={}",
                        batchId);
            }
        } catch (DataAccessException e) {
            logger.warn("touchActivity: liveness stamp failed, ignoring: batchId={}, error={}",
                    batchId, e.getMessage());
        }
    }

    /**
     * Whether a batch is still live and may take further work (030).
     * <p>
     * Used by the Delta v2 resume path: a staged session parked for a reconnect can outlive its
     * batch, because the timeout sweeper reaps an IN_PROGRESS batch after
     * {@code batch.timeout.minutes}. Re-attaching to a reaped batch only surfaces at the final
     * commit, so the resume checks first. A missing batch answers {@code false} rather than
     * throwing — the caller wants a decision, not an exception.
     * </p>
     *
     * @param batchId batch identifier
     * @return true when the batch exists and is IN_PROGRESS
     */
    @Transactional(readOnly = true)
    public boolean isBatchInProgress(UUID batchId) {
        return batchRepository.findById(batchId)
                .map(batch -> batch.getStatus() == BatchStatus.IN_PROGRESS)
                .orElse(false);
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
     * Reap a timed-out batch, but only while it is <em>still</em> expired (030/T06).
     * <p>
     * The timeout sweeper selects expired batches and then transitions them one by one. A live
     * Delta v2 session can touch its batch in that gap; {@link #markBatchNotCompleted(UUID)} would
     * kill it anyway — the very incident feature 029 exists to prevent. (Until 030 the
     * optimistic-lock bump in the old liveness write accidentally blocked that; removing the bump —
     * necessary, since it was crashing the ingest path — also removed the accidental guard.) This
     * conditional update re-checks status and cutoff atomically, so a revived session survives.
     * </p>
     *
     * @param batchId    batch identifier
     * @param cutoffTime the cutoff the sweeper's SELECT used; never recompute it per batch, or a
     *                   batch can be judged against a cutoff it was never selected by
     * @return true when the batch was reaped, false when it was revived, vanished or is terminal
     */
    public boolean markBatchNotCompletedIfStillExpired(UUID batchId, LocalDateTime cutoffTime) {
        // accountId is immutable, so reading it up front is safe and keeps the expiry event
        // publishable without re-reading a row the bulk update has since changed.
        Optional<Batch> existing = batchRepository.findById(batchId);
        if (existing.isEmpty()) {
            logger.debug("Timeout sweep: batch vanished, skipping: batchId={}", batchId);
            return false;
        }
        UUID accountId = existing.get().getAccountId();

        if (batchRepository.markNotCompletedIfStillExpired(batchId, cutoffTime, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
            logger.debug("Timeout sweep: batch revived or already terminal, skipping: batchId={}", batchId);
            return false;
        }

        eventPublisher.publishEvent(new BatchExpiredEvent(batchId, accountId));
        logger.info("Batch marked as NOT_COMPLETED by timeout sweep: batchId={}", batchId);
        return true;
    }

    /**
     * Reclaim a site's active batch when it has been silent since {@code cutoffTime} (033 review).
     *
     * <p>A Delta v2 session that drops is staged for resume in the serving pod's memory, leaving its
     * batch {@code IN_PROGRESS} on purpose. With more than one replica the retry usually lands on a
     * different pod, which knows nothing of that staged entry and is rejected with
     * {@code ACTIVE_SESSION_EXISTS} until the original pod's sweeper runs. Callers use this to
     * unblock a legitimate new session across pods.</p>
     *
     * <p>Safe by construction: it delegates to the same conditional update the timeout sweeper uses,
     * which re-checks status and cutoff atomically, so a session that is merely quiet — and touches
     * its batch before the update lands — is never killed.</p>
     *
     * @param siteId     site whose active batch to reclaim
     * @param cutoffTime reclaim only if the batch has had no activity since this instant
     * @return true when a batch was reclaimed and the caller may retry
     */
    public boolean reclaimAbandonedBatch(UUID siteId, LocalDateTime cutoffTime) {
        return batchRepository.findActiveBySiteId(siteId)
                .map(batch -> markBatchNotCompletedIfStillExpired(batch.getId(), cutoffTime))
                .orElse(false);
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
     * Exception thrown when the site a batch is being started for no longer exists.
     * <p>
     * Extends {@link IllegalArgumentException} so callers that only distinguish "bad request" keep
     * working, while the one caller that maps rejections onto a typed protocol
     * ({@code DeltaIngestionService}) can single it out without catching every
     * {@code IllegalArgumentException} the call might raise.
     * </p>
     */
    public static class SiteNotFoundException extends IllegalArgumentException {
        public SiteNotFoundException(String message) {
            super(message);
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
