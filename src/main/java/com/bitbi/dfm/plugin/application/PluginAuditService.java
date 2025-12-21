package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
