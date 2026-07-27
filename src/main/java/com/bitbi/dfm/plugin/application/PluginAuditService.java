package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import com.bitbi.dfm.plugin.domain.SqlGenerationStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for creating audit log entries for plugin operations.
 *
 * <p>Implements FR-013 (admin audit visibility) and FR-014 (hashed request bodies):</p>
 * <ul>
 *   <li>Logs plugin activations, deactivations, and reactivations</li>
 *   <li>Logs event dispatch success, failure, and timeout</li>
 *   <li>All logging is asynchronous to avoid impacting API performance</li>
 * </ul>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Service
public class PluginAuditService {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditService.class);

    private final PluginAuditLogRepository auditLogRepository;

    public PluginAuditService(PluginAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
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
    @Async("pluginExecutor")
    @Transactional
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

            auditLogRepository.save(auditLog);
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
    @Async("pluginExecutor")
    @Transactional
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

            auditLogRepository.save(auditLog);
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

    // ==================== Plugin History Audit Methods (Feature 014) ====================

    /**
     * Logs that plugin history was cleared for an account.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param deletedCount number of generation records deleted
     * @param deletedFilesCount number of S3 files deleted
     * @param deletedTotalBytes total bytes deleted
     */
    @Async("pluginExecutor")
    @Transactional
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

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: PLUGIN_HISTORY_CLEARED plugin={} account={} deleted={}",
                    pluginId, accountId, deletedCount);
        } catch (Exception e) {
            log.error("Failed to log history cleared audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs the start of SQL regeneration for a batch.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param batchId the batch being regenerated
     * @param originalGenerationId the original generation being superseded (may be null)
     */
    @Async("pluginExecutor")
    @Transactional
    public void logSqlRegenerationStarted(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID originalGenerationId) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            if (originalGenerationId != null) {
                metadata.put("originalGenerationId", originalGenerationId.toString());
            }

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_REGENERATION_STARTED)
                    .withMetadata(metadata);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_REGENERATION_STARTED plugin={} account={} batch={}",
                    pluginId, accountId, batchId);
        } catch (Exception e) {
            log.error("Failed to log SQL regeneration started audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    /**
     * Logs successful completion of SQL regeneration.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param batchId the batch that was regenerated
     * @param originalGenerationId the original generation that was superseded (may be null)
     * @param newGenerationId the new generation created
     * @param stats the generation statistics
     * @param durationMs time taken to regenerate
     */
    @Async("pluginExecutor")
    @Transactional
    public void logSqlRegenerationCompleted(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID originalGenerationId,
            UUID newGenerationId,
            SqlGenerationStats stats,
            long durationMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            if (originalGenerationId != null) {
                metadata.put("originalGenerationId", originalGenerationId.toString());
            }
            metadata.put("newGenerationId", newGenerationId.toString());
            metadata.put("insertCount", stats.inserts());
            metadata.put("updateCount", stats.updates());
            metadata.put("deleteCount", stats.deletes());

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_REGENERATION_COMPLETED)
                    .withMetadata(metadata)
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_REGENERATION_COMPLETED plugin={} account={} batch={} duration={}ms",
                    pluginId, accountId, batchId, durationMs);
        } catch (Exception e) {
            log.error("Failed to log SQL regeneration completed audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    /**
     * Logs a failed SQL regeneration attempt.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param batchId the batch that failed regeneration
     * @param originalGenerationId the original generation ID (may be null)
     * @param errorMessage the error message
     * @param durationMs time elapsed before failure
     */
    @Async("pluginExecutor")
    @Transactional
    public void logSqlRegenerationFailed(
            String pluginId,
            UUID accountId,
            UUID batchId,
            UUID originalGenerationId,
            String errorMessage,
            long durationMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("batchId", batchId.toString());
            if (originalGenerationId != null) {
                metadata.put("originalGenerationId", originalGenerationId.toString());
            }

            PluginAuditLog auditLog = PluginAuditLog.failure(pluginId, accountId,
                            PluginActionType.SQL_REGENERATION_FAILED, errorMessage)
                    .withMetadata(metadata)
                    .withDuration(durationMs);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_REGENERATION_FAILED plugin={} account={} batch={} error={}",
                    pluginId, accountId, batchId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to log SQL regeneration failure audit: plugin={} batch={} error={}",
                    pluginId, batchId, e.getMessage());
        }
    }

    // ==================== Plugin Reinit Audit Methods (Feature 015) ====================

    /**
     * Logs a successful plugin reinitialization.
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param deletedGenerations number of SQL generation records deleted
     * @param deletedS3Files number of S3 files deleted
     * @param sqlGenerationTriggered whether SQL generation was triggered
     * @param batchId the batch used for regeneration (may be null if no batches exist)
     */
    @Async("pluginExecutor")
    @Transactional
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

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: REINIT plugin={} account={} deleted={} triggered={}",
                    pluginId, accountId, deletedGenerations, sqlGenerationTriggered);
        } catch (Exception e) {
            log.error("Failed to log reinit audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
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
     *
     * @param pluginId the plugin identifier
     * @param accountId the account ID
     * @param generationId the deleted generation ID
     * @param batchId the batch ID associated with the generation
     * @param deletedBytes the size of the deleted file in bytes
     */
    @Async("pluginExecutor")
    @Transactional
    public void logGenerationDeleted(
            String pluginId,
            UUID accountId,
            UUID generationId,
            UUID batchId,
            Long deletedBytes) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("generationId", generationId.toString());
            metadata.put("batchId", batchId != null ? batchId.toString() : null);
            metadata.put("deletedBytes", deletedBytes != null ? deletedBytes : 0L);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.SQL_GENERATION_DELETED)
                    .withMetadata(metadata);

            auditLogRepository.save(auditLog);
            log.debug("Audit logged: SQL_GENERATION_DELETED plugin={} account={} generation={}",
                    pluginId, accountId, generationId);
        } catch (Exception e) {
            log.error("Failed to log generation deletion audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
        }
    }

    /**
     * Logs a Parquet Export password rotation (028).
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account that rotated its password
     */
    @Async("pluginExecutor")
    @Transactional
    public void logPasswordRotated(String pluginId, UUID accountId) {
        try {
            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                    PluginActionType.PASSWORD_ROTATED);
            auditLogRepository.save(auditLog);
            log.debug("Audit logged: PASSWORD_ROTATED plugin={} account={}", pluginId, accountId);
        } catch (Exception e) {
            log.error("Failed to log password rotation audit: plugin={} account={} error={}",
                    pluginId, accountId, e.getMessage());
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
    @Async("pluginExecutor")
    @Transactional
    public void logLinkConsumed(String pluginId, UUID accountId, String fileName, String s3Key) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", fileName);
            metadata.put("s3Key", s3Key);

            PluginAuditLog auditLog = PluginAuditLog.success(pluginId, accountId,
                            PluginActionType.LINK_CONSUMED)
                    .withMetadata(metadata);
            auditLogRepository.save(auditLog);
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
