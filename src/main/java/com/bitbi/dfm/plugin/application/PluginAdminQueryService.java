package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.presentation.dto.PluginAuditLogEntryDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginConfigResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service for admin queries on plugin data.
 *
 * <p>Implements FR-013 requirements:</p>
 * <ul>
 *   <li>List all registered plugins with their configuration</li>
 *   <li>Query audit logs with filters (pluginId, accountId, actionType, success, date range)</li>
 * </ul>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Service
@Transactional(readOnly = true)
public class PluginAdminQueryService {

    private static final Logger log = LoggerFactory.getLogger(PluginAdminQueryService.class);

    private final PluginRegistry pluginRegistry;
    private final PluginConfigRepository pluginConfigRepository;
    private final PluginAuditLogRepository auditLogRepository;

    public PluginAdminQueryService(
            PluginRegistry pluginRegistry,
            PluginConfigRepository pluginConfigRepository,
            PluginAuditLogRepository auditLogRepository) {
        this.pluginRegistry = pluginRegistry;
        this.pluginConfigRepository = pluginConfigRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Lists all registered plugins with their configuration and runtime information.
     *
     * <p>Combines data from:</p>
     * <ul>
     *   <li>PluginConfig (database) - display name, enabled status, client_id</li>
     *   <li>Plugin (code) - version, supported events</li>
     * </ul>
     *
     * @return list of registered plugin configurations
     */
    public List<PluginConfigResponseDto> listRegisteredPlugins() {
        log.debug("Listing all registered plugins");

        List<PluginConfig> configs = pluginConfigRepository.findAll();

        return configs.stream()
                .map(config -> {
                    // Try to get additional info from registered plugin
                    return pluginRegistry.findById(config.getPluginId())
                            .map(plugin -> PluginConfigResponseDto.fromEntity(config, plugin))
                            .orElseGet(() -> PluginConfigResponseDto.fromEntityOnly(config));
                })
                .toList();
    }

    /**
     * Queries audit logs with optional filters.
     *
     * <p>Supports filtering by:</p>
     * <ul>
     *   <li>pluginId - specific plugin</li>
     *   <li>accountId - specific account</li>
     *   <li>actionType - ACTIVATE, DEACTIVATE, EVENT_DISPATCHED, etc.</li>
     *   <li>success - true/false</li>
     *   <li>from/to - date range (occurredAt)</li>
     * </ul>
     *
     * @param pluginId   filter by plugin ID (null for all)
     * @param accountId  filter by account ID (null for all)
     * @param actionType filter by action type (null for all)
     * @param success    filter by success status (null for all)
     * @param from       start of date range (inclusive, null for no start)
     * @param to         end of date range (exclusive, null for no end)
     * @param pageable   pagination parameters
     * @return paginated audit log entries
     */
    public Page<PluginAuditLogEntryDto> queryAuditLogs(
            String pluginId,
            UUID accountId,
            PluginActionType actionType,
            Boolean success,
            Instant from,
            Instant to,
            Pageable pageable) {

        log.debug("Querying audit logs: pluginId={}, accountId={}, actionType={}, success={}, from={}, to={}",
                pluginId, accountId, actionType, success, from, to);

        Page<PluginAuditLog> auditLogs = auditLogRepository.findByFilters(
                pluginId, accountId, actionType, success, from, to, pageable);

        return auditLogs.map(PluginAuditLogEntryDto::fromEntity);
    }

    /**
     * Gets audit logs for a specific plugin.
     *
     * @param pluginId the plugin identifier
     * @param pageable pagination parameters
     * @return paginated audit log entries
     */
    public Page<PluginAuditLogEntryDto> getAuditLogsByPlugin(String pluginId, Pageable pageable) {
        log.debug("Getting audit logs for plugin: {}", pluginId);

        Page<PluginAuditLog> auditLogs = auditLogRepository.findByPluginId(pluginId, pageable);
        return auditLogs.map(PluginAuditLogEntryDto::fromEntity);
    }

    /**
     * Gets audit logs for a specific account.
     *
     * @param accountId the account identifier
     * @param pageable  pagination parameters
     * @return paginated audit log entries
     */
    public Page<PluginAuditLogEntryDto> getAuditLogsByAccount(UUID accountId, Pageable pageable) {
        log.debug("Getting audit logs for account: {}", accountId);

        Page<PluginAuditLog> auditLogs = auditLogRepository.findByAccountId(accountId, pageable);
        return auditLogs.map(PluginAuditLogEntryDto::fromEntity);
    }

    /**
     * Counts audit log entries by action type within a date range.
     * Useful for metrics and monitoring dashboards.
     *
     * @param actionType the action type to count
     * @param from       start of date range
     * @param to         end of date range
     * @return count of matching entries
     */
    public long countByActionType(PluginActionType actionType, Instant from, Instant to) {
        return auditLogRepository.countByActionTypeAndDateRange(actionType, from, to);
    }
}
