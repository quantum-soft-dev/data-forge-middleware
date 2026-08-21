package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Service for creating audit log entries for plugin operations.
 *
 * <p>Implements FR-013 (admin audit visibility) and FR-014 (hashed request bodies):</p>
 * <ul>
 *   <li>Logs plugin activations, deactivations, and reactivations</li>
 *   <li>Logs event dispatch success, failure, and timeout</li>
 *   <li>Every entry reaches the database off the request thread, to avoid impacting API
 *       performance — either straight away via {@code @Async}, or, for entries describing a state
 *       change, once the caller's transaction resolves (see {@link PluginAuditEntryReadyEvent})</li>
 * </ul>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Service
public class PluginAuditService {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditService.class);

    /**
     * Error message of the entry written when a clear/reinit/delete rolls back: its S3 objects were
     * deleted before the commit point, so the rows came back and the files did not.
     */
    private static final String S3_DELETED_BEFORE_ROLLBACK =
            "Rolled back after the S3 files were deleted: database state was restored, the files were not";

    private final PluginAuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PluginAuditService(PluginAuditLogRepository auditLogRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.auditLogRepository = auditLogRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Hands an entry to {@code PluginAuditEventListener}, which persists it once the caller's
     * transaction commits.
     * <p>
     * Used only for entries describing a state change. Runs on the caller's thread inside their
     * transaction, so it must stay cheap and must not be {@code @Async}: the transaction context
     * that makes the deferral possible is thread-bound and would already be gone on another thread.
     * </p>
     */
    private void publishAfterCommit(PluginAuditLog entry) {
        eventPublisher.publishEvent(new PluginAuditEntryReadyEvent(entry));
    }

    /**
     * Same deferral, for a state change whose side effects may not be transactional: the S3 objects
     * are already destroyed by the time the caller's transaction resolves. On commit the entry is
     * written as usual; on rollback {@code rollbackEntry} records that the database was restored
     * over files that no longer exist, instead of leaving that divergence unlogged.
     * <p>
     * {@code deletedFiles} is the number of objects that actually left the bucket, not the number
     * attempted. At zero — a blank key, a failed delete, nothing to delete — the operation was
     * wholly transactional after all, so the rollback entry is dropped: it would otherwise assert
     * a destruction that never happened.
     * </p>
     * <p>
     * For the same reason {@code rollbackEntry} must not simply reuse the success entry's metadata:
     * the row counts there, and the bytes attributed to them, describe deletions the rollback undid.
     * It carries only what the rollback left standing.
     * </p>
     */
    private void publishAfterCommit(PluginAuditLog entry, long deletedFiles,
                                    Supplier<PluginAuditLog> rollbackEntry) {
        eventPublisher.publishEvent(deletedFiles > 0
                ? new PluginAuditEntryReadyEvent(entry, rollbackEntry.get())
                : new PluginAuditEntryReadyEvent(entry));
    }

    /**
     * Logs a successful plugin activation.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account that activated the plugin
     * @param clientId  the OAuth client ID (from JWT azp claim)
     * @param durationMs operation duration in milliseconds
     */
    @Async("pluginExecutor")
    @Transactional
    public void logActivation(String pluginId, UUID accountId, String clientId, long durationMs) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId, PluginActionType.ACTIVATE)
                    .withClientId(clientId)
                    .withDuration(durationMs)
                    .withResponseStatus(201);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: ACTIVATE plugin={} account={}", pluginId, accountId);
        } catch (Exception e) {
            // Don't fail the operation if audit logging fails
            log.error("Failed to log activation audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a successful plugin reactivation.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account that reactivated the plugin
     * @param clientId  the OAuth client ID
     * @param durationMs operation duration in milliseconds
     */
    @Async("pluginExecutor")
    @Transactional
    public void logReactivation(String pluginId, UUID accountId, String clientId, long durationMs) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId, PluginActionType.REACTIVATE)
                    .withClientId(clientId)
                    .withDuration(durationMs)
                    .withResponseStatus(200);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: REACTIVATE plugin={} account={}", pluginId, accountId);
        } catch (Exception e) {
            log.error("Failed to log reactivation audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a successful plugin deactivation.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account that deactivated the plugin
     * @param clientId  the OAuth client ID
     * @param durationMs operation duration in milliseconds
     */
    @Async("pluginExecutor")
    @Transactional
    public void logDeactivation(String pluginId, UUID accountId, String clientId, long durationMs) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId, PluginActionType.DEACTIVATE)
                    .withClientId(clientId)
                    .withDuration(durationMs)
                    .withResponseStatus(204);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: DEACTIVATE plugin={} account={}", pluginId, accountId);
        } catch (Exception e) {
            log.error("Failed to log deactivation audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a successful event dispatch to a plugin.
     *
     * @param pluginId   the plugin that received the event
     * @param accountId  the account the event was for
     * @param durationMs time taken to execute the plugin
     */
    @Async("pluginExecutor")
    @Transactional
    public void logEventDispatch(String pluginId, UUID accountId, long durationMs) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId, PluginActionType.EVENT_DISPATCHED)
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: EVENT_DISPATCHED plugin={} account={} duration={}ms",
                    pluginId, accountId, durationMs);
        } catch (Exception e) {
            log.error("Failed to log event dispatch audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a failed event dispatch to a plugin.
     *
     * @param pluginId     the plugin that failed
     * @param accountId    the account the event was for
     * @param errorMessage the error message
     * @param durationMs   time taken before failure
     */
    @Async("pluginExecutor")
    @Transactional
    public void logEventFailure(String pluginId, UUID accountId, String errorMessage, long durationMs) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId, PluginActionType.EVENT_FAILED, errorMessage)
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: EVENT_FAILED plugin={} account={} error={}",
                    pluginId, accountId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log event failure audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a plugin execution timeout.
     *
     * @param pluginId   the plugin that timed out
     * @param accountId  the account the event was for
     * @param durationMs time elapsed before timeout
     */
    @Async("pluginExecutor")
    @Transactional
    public void logEventTimeout(String pluginId, UUID accountId, long durationMs) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.failure(
                    pluginId, accountId, PluginActionType.EVENT_TIMEOUT,
                    "Plugin execution timed out after " + durationMs + "ms")
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: EVENT_TIMEOUT plugin={} account={} duration={}ms",
                    pluginId, accountId, durationMs);
        } catch (Exception e) {
            log.error("Failed to log event timeout audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a failed activation attempt.
     *
     * @param pluginId     the plugin identifier
     * @param accountId    the account that attempted activation (may be null)
     * @param clientId     the OAuth client ID
     * @param errorMessage the error message
     * @param durationMs   operation duration
     * @param responseStatus HTTP response status code
     */
    @Async("pluginExecutor")
    @Transactional
    public void logActivationFailure(
            String pluginId,
            UUID accountId,
            String clientId,
            String errorMessage,
            long durationMs,
            int responseStatus) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId, PluginActionType.ACTIVATE, errorMessage)
                    .withClientId(clientId)
                    .withDuration(durationMs)
                    .withResponseStatus(responseStatus);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: ACTIVATE_FAILED plugin={} account={} status={}",
                    pluginId, accountId, responseStatus);
        } catch (Exception e) {
            log.error("Failed to log activation failure audit: plugin={} error={}",
                    pluginId, e.getMessage());
        }
    }

    /**
     * Logs a failed deactivation attempt.
     *
     * @param pluginId     the plugin identifier
     * @param accountId    the account that attempted deactivation
     * @param clientId     the OAuth client ID
     * @param errorMessage the error message
     * @param durationMs   operation duration
     * @param responseStatus HTTP response status code
     */
    @Async("pluginExecutor")
    @Transactional
    public void logDeactivationFailure(
            String pluginId,
            UUID accountId,
            String clientId,
            String errorMessage,
            long durationMs,
            int responseStatus) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId, PluginActionType.DEACTIVATE, errorMessage)
                    .withClientId(clientId)
                    .withDuration(durationMs)
                    .withResponseStatus(responseStatus);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: DEACTIVATE_FAILED plugin={} account={} status={}",
                    pluginId, accountId, responseStatus);
        } catch (Exception e) {
            log.error("Failed to log deactivation failure audit: plugin={} error={}",
                    pluginId, e.getMessage());
        }
    }

    // ==================== SQL Generation Audit Methods ====================

    /**
     * Logs the start of SQL generation for a batch.
     *
     * @param pluginId  the plugin identifier (e.g., "bit-bi")
     * @param accountId the account that owns the batch
     * @param batchId   the batch being processed
     * @param siteId    the site the batch belongs to
     */
    @Async("pluginExecutor")
    @Transactional
    public void logSqlGenerationStarted(String pluginId, UUID accountId, UUID batchId, UUID siteId) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            metadata.put("siteId", siteId.toString());

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_STARTED)
                    .withMetadata(metadata);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_GENERATION_STARTED plugin={} account={} batch={}",
                    pluginId, accountId, batchId);
        } catch (Exception e) {
            log.error("Failed to log SQL generation started audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    /**
     * Logs successful completion of SQL generation for a batch.
     *
     * @param pluginId   the plugin identifier
     * @param accountId  the account that owns the batch
     * @param batchId    the batch that was processed
     * @param siteId     the site the batch belongs to
     * @param stats      the generation statistics (inserts, updates, deletes, files)
     * @param s3Key      the S3 key where the SQL file was stored
     * @param durationMs time taken to generate the SQL file
     */
    public void logSqlGenerationCompleted(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID siteId,
            SqlGenerationStats stats,
            String s3Key,
            long durationMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            metadata.put("siteId", siteId.toString());
            metadata.put("insertCount", stats.inserts());
            metadata.put("updateCount", stats.updates());
            metadata.put("deleteCount", stats.deletes());
            metadata.put("filesProcessed", stats.filesProcessed());
            metadata.put("s3Key", s3Key);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_COMPLETED)
                    .withMetadata(metadata)
                    .withDuration(durationMs);

            publishAfterCommit(auditLog);
            log.debug("Audit logged: SQL_GENERATION_COMPLETED plugin={} account={} batch={} " +
                            "inserts={} updates={} deletes={} duration={}ms",
                    pluginId, accountId, batchId, stats.inserts(), stats.updates(), stats.deletes(), durationMs);
        } catch (Exception e) {
            log.error("Failed to log SQL generation completed audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    /**
     * Logs successful completion of SQL generation when no changes were detected.
     *
     * <p>This is a success case - the batch was processed correctly, but the CSV files
     * were identical to the previous batch, so no SQL statements were generated.</p>
     *
     * @param pluginId   the plugin identifier
     * @param accountId  the account that owns the batch
     * @param batchId    the batch that was processed
     * @param siteId     the site the batch belongs to
     * @param durationMs time taken to compare files
     */
    public void logSqlGenerationCompletedNoChanges(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID siteId,
            long durationMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            metadata.put("siteId", siteId.toString());
            metadata.put("insertCount", 0);
            metadata.put("updateCount", 0);
            metadata.put("deleteCount", 0);
            metadata.put("filesProcessed", 0);
            metadata.put("noChangesDetected", true);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_COMPLETED)
                    .withMetadata(metadata)
                    .withDuration(durationMs);

            publishAfterCommit(auditLog);
            log.debug("Audit logged: SQL_GENERATION_COMPLETED (no changes) plugin={} account={} batch={} duration={}ms",
                    pluginId, accountId, batchId, durationMs);
        } catch (Exception e) {
            log.error("Failed to log SQL generation completed (no changes) audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    /**
     * Logs a failed SQL generation attempt.
     *
     * @param pluginId     the plugin identifier
     * @param accountId    the account that owns the batch
     * @param batchId      the batch that failed processing
     * @param siteId       the site the batch belongs to
     * @param errorMessage the error message describing the failure
     * @param durationMs   time elapsed before failure
     */
    @Async("pluginExecutor")
    @Transactional
    public void logSqlGenerationFailed(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID siteId,
            String errorMessage,
            long durationMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            metadata.put("siteId", siteId.toString());

            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_FAILED, errorMessage)
                    .withMetadata(metadata)
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_GENERATION_FAILED plugin={} account={} batch={} error={}",
                    pluginId, accountId, batchId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log SQL generation failure audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    /**
     * Logs that this attempt lost the unique claim and adopted the winner's generation.
     *
     * <p>Written immediately, like {@link #logSqlGenerationStarted}: the attempt happened
     * regardless of any surrounding transaction, and the winner already owns the row. Deferring
     * it to AFTER_COMMIT would drop the terminal companion of STARTED if the caller rolled back
     * after adopting — the unpaired line this method exists to close (issue #260).</p>
     *
     * @param pluginId      the plugin identifier
     * @param accountId     the account that owns the batch
     * @param batchId       the batch both workers generated for
     * @param siteId        the site the batch belongs to
     * @param generationId  the adopted generation (the winner's row)
     * @param winnerS3Key   the surviving SQL object; this attempt's own key was deleted
     * @param durationMs    time this attempt spent rendering before it lost the claim
     */
    @Async("pluginExecutor")
    @Transactional
    public void logSqlGenerationAdopted(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID siteId,
            UUID generationId,
            String winnerS3Key,
            long durationMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            metadata.put("siteId", siteId.toString());
            metadata.put("generationId", generationId.toString());
            metadata.put("s3Key", winnerS3Key);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_ADOPTED)
                    .withMetadata(metadata)
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_GENERATION_ADOPTED plugin={} account={} batch={} "
                            + "generation={} s3Key={} duration={}ms",
                    pluginId, accountId, batchId, generationId, winnerS3Key, durationMs);
        } catch (Exception e) {
            log.error("Failed to log SQL generation adopted audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    // ==================== Plugin History Audit Methods (Feature 014) ====================

    /**
     * Logs that plugin history was cleared for an account.
     * <p>
     * The S3 objects are deleted before the caller commits, so a rollback cannot take them back:
     * this records a failure on that path rather than nothing at all — but only when
     * {@code deletedFilesCount} is above zero. If nothing left the bucket there is nothing a
     * rollback failed to undo, and claiming otherwise would be its own false record.
     * </p>
     * <p>
     * The rollback entry keeps only {@code deletedFilesCount}. {@code deletedCount} counts rows the
     * rollback restored, and {@code deletedTotalBytes} is the size of <em>every</em> generation, not
     * of the subset whose files actually left the bucket — on a partial S3 failure it would
     * overstate the loss.
     * </p>
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param deletedCount number of generation records deleted
     * @param deletedFilesCount number of S3 files deleted
     * @param deletedTotalBytes total bytes deleted
     */
    public void logHistoryCleared(
            String pluginId,
            UUID accountId,
            long deletedCount,
            long deletedFilesCount,
            long deletedTotalBytes) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deletedCount", deletedCount);
            metadata.put("deletedFilesCount", deletedFilesCount);
            metadata.put("totalBytes", deletedTotalBytes);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.PLUGIN_HISTORY_CLEARED)
                    .withMetadata(metadata);

            publishAfterCommit(auditLog, deletedFilesCount, () -> {
                Map<String, Object> rollbackMetadata = new HashMap<>();
                rollbackMetadata.put("deletedFilesCount", deletedFilesCount);
                return PluginAuditLog.failure(pluginId, accountId,
                                PluginActionType.PLUGIN_HISTORY_CLEARED, S3_DELETED_BEFORE_ROLLBACK)
                        .withMetadata(rollbackMetadata);
            });
            log.debug("Audit logged: PLUGIN_HISTORY_CLEARED plugin={} account={} deleted={}",
                    pluginId, accountId, deletedCount);
        } catch (Exception e) {
            log.error("Failed to log history cleared audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    // ==================== Plugin Reinit Audit Methods (Feature 015) ====================

    /**
     * Logs a successful plugin reinitialization.
     * <p>
     * Like {@link #logHistoryCleared}, the S3 deletions outlive a rollback, so that path gets a
     * failure entry instead of silence — only when {@code deletedS3Files} is above zero, and
     * carrying only {@code deletedS3Files}. The deleted generations and the new baseline batch are
     * both back where they were by then, so naming them would misdescribe the account's state.
     * </p>
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param deletedGenerations number of SQL generation records deleted
     * @param deletedS3Files number of S3 files deleted
     * @param sqlGenerationTriggered always {@code false} — reinit no longer triggers SQL
     *                                generation (recorded for the audit row's shape)
     * @param batchId the batch set as the new baseline (may be null if no batches exist)
     */
    public void logReinit(
            String pluginId,
            UUID accountId,
            long deletedGenerations,
            long deletedS3Files,
            boolean sqlGenerationTriggered,
            UUID batchId) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deletedGenerations", deletedGenerations);
            metadata.put("deletedS3Files", deletedS3Files);
            metadata.put("sqlGenerationTriggered", sqlGenerationTriggered);
            metadata.put("success", true);
            if (batchId != null) {
                metadata.put("batchId", batchId.toString());
            }

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId, PluginActionType.REINIT)
                    .withMetadata(metadata);

            publishAfterCommit(auditLog, deletedS3Files, () -> {
                Map<String, Object> rollbackMetadata = new HashMap<>();
                rollbackMetadata.put("deletedS3Files", deletedS3Files);
                rollbackMetadata.put("success", false);
                return PluginAuditLog.failure(pluginId, accountId,
                                PluginActionType.REINIT, S3_DELETED_BEFORE_ROLLBACK)
                        .withMetadata(rollbackMetadata);
            });
            log.debug("Audit logged: REINIT plugin={} account={} deleted={} triggered={}",
                    pluginId, accountId, deletedGenerations, sqlGenerationTriggered);
        } catch (Exception e) {
            log.error("Failed to log reinit audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs the automatic baseline recapture that follows a site history wipe (issue #89).
     * <p>
     * Deferred to after commit like every other state-change entry: the recapture and the entry
     * describing it are one transaction, so a rollback takes both. It replaces the "reinit
     * required" warning for this path — an ordinary re-baseline still raises that warning and
     * still needs a manual reinit.
     * </p>
     *
     * @param accountId     the account owning the site
     * @param siteId        the wiped site whose baselines were re-captured
     * @param checkpointSeq the checkpoint seq the new baselines were taken at
     */
    public void logDeltaAutoReinit(UUID accountId, UUID siteId, long checkpointSeq) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("siteId", siteId.toString());
            metadata.put("checkpointSeq", checkpointSeq);
            metadata.put("trigger", "SITE_HISTORY_WIPE");

            publishAfterCommit(PluginAuditLog
                    .success("bit-bi", accountId, PluginActionType.DELTA_AUTO_REINIT)
                    .withMetadata(metadata));
            log.debug("Audit logged: DELTA_AUTO_REINIT account={} site={} seq={}",
                    accountId, siteId, checkpointSeq);
        } catch (Exception e) {
            log.error("Failed to log delta auto-reinit audit: account={} site={} error={}",
                    accountId, siteId, e.getMessage());
        }
    }

    /**
     * Logs a failed plugin reinitialization attempt.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param errorMessage the error message describing the failure
     */
    @Async("pluginExecutor")
    @Transactional
    public void logReinitFailed(
            String pluginId,
            UUID accountId,
            String errorMessage) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("deletedGenerations", 0);
            metadata.put("success", false);

            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId,
                            PluginActionType.REINIT, errorMessage)
                    .withMetadata(metadata);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: REINIT_FAILED plugin={} account={} error={}",
                    pluginId, accountId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log reinit failure audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a SQL generation deletion event.
     * <p>
     * Like {@link #logHistoryCleared}, the S3 deletion outlives a rollback, so that path gets a
     * failure entry instead of silence — only when {@code s3FileDeleted} is true. Here the whole
     * metadata carries over: one named file of known size, so every number in it still describes
     * exactly what was lost, and {@code generationId} names the restored row the file belonged to.
     * </p>
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param generationId the deleted generation ID
     * @param batchId the batch ID associated with the generation
     * @param deletedBytes the size of the generation's file in bytes
     * @param s3FileDeleted whether the S3 object actually left the bucket — false for a blank key
     *                      or a failed delete, in which case nothing was freed and a rollback
     *                      undoes the whole operation
     */
    public void logGenerationDeleted(
            String pluginId,
            UUID accountId,
            UUID generationId,
            UUID batchId,
            Long deletedBytes,
            boolean s3FileDeleted) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("generationId", generationId.toString());
            metadata.put("batchId", batchId != null ? batchId.toString() : null);
            metadata.put("deletedFilesCount", s3FileDeleted ? 1 : 0);
            metadata.put("deletedBytes", s3FileDeleted && deletedBytes != null ? deletedBytes : 0L);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_DELETED)
                    .withMetadata(metadata);

            publishAfterCommit(auditLog, s3FileDeleted ? 1L : 0L, () -> PluginAuditLog.failure(
                            pluginId, accountId,
                            PluginActionType.SQL_GENERATION_DELETED, S3_DELETED_BEFORE_ROLLBACK)
                    .withMetadata(new HashMap<>(metadata)));
            log.debug("Audit logged: SQL_GENERATION_DELETED plugin={} account={} generation={}",
                    pluginId, accountId, generationId);
        } catch (Exception e) {
            log.error("Failed to log generation deletion audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a credential rotation.
     * <p>
     * Deferred: the entry is persisted only once the rotating transaction commits, so a rotation
     * that rolls back leaves nothing claiming it happened.
     * </p>
     *
     * @param pluginId   the plugin identifier
     * @param accountId  the account whose credential was rotated
     * @param actionType API_KEY_ROTATED or PASSWORD_ROTATED
     */
    public void logCredentialRotated(String pluginId, UUID accountId, PluginActionType actionType) {
        try {
            publishAfterCommit(PluginAuditLog.success(pluginId, accountId, actionType));
            log.debug("Audit logged: {} plugin={} account={}", actionType, pluginId, accountId);
        } catch (Exception e) {
            log.error("Failed to log credential rotation audit: plugin={} account={} action={} error={}",
                    pluginId, accountId, actionType, e.getMessage());
        }
    }

    /**
     * Logs a served Parquet Export file listing (028).
     *
     * @param pluginId  the plugin identifier
     * @param accountId the authenticated account
     * @param filters   applied listing filters (since/siteId/table/type/page/size)
     * @param fileCount number of files (one-time links) returned
     */
    @Async("pluginExecutor")
    @Transactional
    public void logFilesListed(String pluginId, UUID accountId, Map<String, Object> filters, int fileCount) {
        try {
            Map<String, Object> metadata = new HashMap<>(filters);
            metadata.put("fileCount", fileCount);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.FILES_LISTED)
                    .withMetadata(metadata);
            auditLogRepository.save(auditLog);
            log.debug("Audit logged: FILES_LISTED plugin={} account={} files={}", pluginId, accountId, fileCount);
        } catch (Exception e) {
            log.error("Failed to log files listing audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a consumed one-time download link (028).
     *
     * @param pluginId  the plugin identifier
     * @param accountId the owning account
     * @param fileName  downloaded file name
     * @param s3Key     downloaded object key
     */
    public void logLinkConsumed(String pluginId, UUID accountId, String fileName, String s3Key) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", fileName);
            metadata.put("s3Key", s3Key);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.LINK_CONSUMED)
                    .withMetadata(metadata);
            publishAfterCommit(auditLog);
            log.debug("Audit logged: LINK_CONSUMED plugin={} account={} file={}", pluginId, accountId, fileName);
        } catch (Exception e) {
            log.error("Failed to log link consumption audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a rejected one-time download attempt (028). accountId may be null when the token is
     * unknown (there is no activation to attribute it to).
     *
     * @param pluginId  the plugin identifier
     * @param accountId the owning account, or null for unknown tokens
     * @param reason    rejection reason: consumed | expired | unknown | inactive
     */
    @Async("pluginExecutor")
    @Transactional
    public void logLinkRejected(String pluginId, UUID accountId, String reason) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("reason", reason);

            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId,
                            PluginActionType.LINK_REJECTED, "Download link rejected: " + reason)
                    .withMetadata(metadata);
            auditLogRepository.save(auditLog);
            log.debug("Audit logged: LINK_REJECTED plugin={} account={} reason={}", pluginId, accountId, reason);
        } catch (Exception e) {
            log.error("Failed to log link rejection audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }
}
