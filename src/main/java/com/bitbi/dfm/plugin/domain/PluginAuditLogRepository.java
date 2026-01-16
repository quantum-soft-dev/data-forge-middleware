package com.bitbi.dfm.plugin.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository interface for PluginAuditLog entities.
 */
public interface PluginAuditLogRepository {

    /**
     * Saves an audit log entry.
     * @param auditLog the audit log to save
     * @return the saved audit log
     */
    PluginAuditLog save(PluginAuditLog auditLog);

    /**
     * Finds audit logs with optional filters.
     * @param pluginId filter by plugin ID (null for all)
     * @param accountId filter by account ID (null for all)
     * @param actionType filter by action type as string (null for all)
     * @param success filter by success status (null for all)
     * @param from start of date range (inclusive, null for no start)
     * @param to end of date range (exclusive, null for no end)
     * @param pageable pagination parameters
     * @return page of matching audit logs
     */
    Page<PluginAuditLog> findByFilters(
            String pluginId,
            UUID accountId,
            String actionType,
            Boolean success,
            Instant from,
            Instant to,
            Pageable pageable);

    /**
     * Finds all audit logs for a specific plugin.
     * @param pluginId the plugin identifier
     * @param pageable pagination parameters
     * @return page of audit logs
     */
    Page<PluginAuditLog> findByPluginId(String pluginId, Pageable pageable);

    /**
     * Finds all audit logs for a specific account.
     * @param accountId the account identifier
     * @param pageable pagination parameters
     * @return page of audit logs
     */
    Page<PluginAuditLog> findByAccountId(UUID accountId, Pageable pageable);

    /**
     * Counts audit logs by action type within a date range.
     * Used for metrics and monitoring.
     * @param actionType the action type
     * @param from start of date range
     * @param to end of date range
     * @return count of matching logs
     */
    long countByActionTypeAndDateRange(PluginActionType actionType, Instant from, Instant to);

    /**
     * Finds audit logs for a specific plugin and account.
     * Used for user-facing plugin log viewing.
     *
     * @param pluginId  the plugin identifier (e.g., "bit-bi")
     * @param accountId the account identifier
     * @param pageable  pagination parameters
     * @return page of audit logs ordered by occurredAt DESC
     */
    Page<PluginAuditLog> findByPluginIdAndAccountId(String pluginId, UUID accountId, Pageable pageable);

    /**
     * Finds audit logs for a specific plugin and account with optional filters.
     * Used for user-facing plugin log viewing with filtering support.
     *
     * @param pluginId  the plugin identifier (e.g., "bit-bi")
     * @param accountId the account identifier
     * @param siteId    filter by site ID in metadata (null for all)
     * @param from      start of date range (inclusive, null for no start)
     * @param to        end of date range (exclusive, null for no end)
     * @param pageable  pagination parameters
     * @return page of audit logs ordered by occurredAt DESC
     */
    Page<PluginAuditLog> findByPluginIdAndAccountIdWithFilters(
            String pluginId,
            UUID accountId,
            UUID siteId,
            Instant from,
            Instant to,
            Pageable pageable);
}
