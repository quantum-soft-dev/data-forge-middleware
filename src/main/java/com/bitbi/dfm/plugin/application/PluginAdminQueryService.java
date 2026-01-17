package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.plugin.domain.*;
import com.bitbi.dfm.plugin.presentation.dto.AdminAccountPluginDto;
import com.bitbi.dfm.plugin.presentation.dto.BatchWithoutSqlDto;
import com.bitbi.dfm.plugin.presentation.dto.BatchWithSqlStatusDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginAuditLogEntryDto;
import com.bitbi.dfm.plugin.presentation.dto.PluginConfigResponseDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
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
    private final AccountPluginRepository accountPluginRepository;
    private final PluginSqlGenerationRepository sqlGenerationRepository;
    private final BatchRepository batchRepository;
    private final SiteRepository siteRepository;

    public PluginAdminQueryService(
            PluginRegistry pluginRegistry,
            PluginConfigRepository pluginConfigRepository,
            PluginAuditLogRepository auditLogRepository,
            AccountPluginRepository accountPluginRepository,
            PluginSqlGenerationRepository sqlGenerationRepository,
            BatchRepository batchRepository,
            SiteRepository siteRepository) {
        this.pluginRegistry = pluginRegistry;
        this.pluginConfigRepository = pluginConfigRepository;
        this.auditLogRepository = auditLogRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.sqlGenerationRepository = sqlGenerationRepository;
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
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

        String actionTypeStr = actionType != null ? actionType.name() : null;
        Page<PluginAuditLog> auditLogs = auditLogRepository.findByFilters(
                pluginId, accountId, actionTypeStr, success, from, to, pageable);

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

    // ==================== Account-Plugin Listing (Feature 014) ====================

    /**
     * Lists all account-plugin activations for a specific plugin with generation counts.
     * Used in admin SQL History tab to select which account to view history for.
     *
     * @param pluginId the plugin identifier
     * @param pageable pagination parameters
     * @return page of account-plugin activations with generation counts
     */
    public Page<AdminAccountPluginDto> listAccountPluginsForPlugin(String pluginId, Pageable pageable) {
        log.debug("Listing account-plugins for plugin: {}", pluginId);

        // Get plugin display name
        String pluginName = pluginConfigRepository.findByPluginId(pluginId)
                .map(PluginConfig::getDisplayName)
                .orElse(pluginId);

        Page<AccountPlugin> accountPlugins = accountPluginRepository.findByPluginIdAndActiveTrue(pluginId, pageable);

        return accountPlugins.map(ap -> {
            long generationCount = sqlGenerationRepository.countByAccountPluginId(ap.getId());
            return AdminAccountPluginDto.fromEntity(ap, pluginName, generationCount);
        });
    }

    // ==================== Batches Without SQL Generation (Manual SQL Feature) ====================

    /**
     * Finds completed batches for an account that don't have SQL generation.
     * Used by admin to identify batches that may need manual SQL generation.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account identifier
     * @param pageable  pagination parameters
     * @return list of batches without SQL generation
     */
    public Page<BatchWithoutSqlDto> findBatchesWithoutSql(String pluginId, UUID accountId, Pageable pageable) {
        log.debug("Finding batches without SQL for plugin={}, account={}", pluginId, accountId);

        // Find the account-plugin activation
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(accountId, pluginId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Plugin " + pluginId + " is not activated for account " + accountId));

        // Get set of batch IDs that already have SQL generated
        java.util.Set<UUID> generatedBatchIds = sqlGenerationRepository
                .findGeneratedBatchIdsByAccountPluginId(accountPlugin.getId());

        // Get baseline batch ID (SQL should not be generated for baseline)
        UUID baselineBatchId = accountPlugin.getBaselineBatchId();

        // Get all completed batches for the account
        Page<Batch> completedBatches = batchRepository.findCompletedByAccountId(accountId, pageable);

        // Map to DTO - only include batches without SQL
        List<BatchWithoutSqlDto> filteredBatches = completedBatches.getContent().stream()
                .filter(batch -> !generatedBatchIds.contains(batch.getId()))
                .map(batch -> {
                    boolean isBaseline = batch.getId().equals(baselineBatchId);

                    // Get site domain
                    String siteDomain = siteRepository.findById(batch.getSiteId())
                            .map(Site::getDisplayDomain)
                            .orElse("unknown");

                    return new BatchWithoutSqlDto(
                            batch.getId(),
                            batch.getSiteId(),
                            siteDomain,
                            batch.getStatus().name(),
                            batch.getCompletedAt() != null
                                    ? batch.getCompletedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                                    : null,
                            batch.getUploadedFilesCount(),
                            batch.getTotalSize(),
                            isBaseline
                    );
                })
                .toList();

        // Return as page - note: totalElements may not be accurate after filtering
        return new org.springframework.data.domain.PageImpl<>(
                filteredBatches, pageable, completedBatches.getTotalElements());
    }

    /**
     * Finds completed batches without SQL generation (excluding baseline).
     * Only returns batches that actually need SQL generation.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account identifier
     * @param pageable  pagination parameters
     * @return list of batches without SQL generation (excluding baseline)
     */
    public java.util.List<BatchWithoutSqlDto> findBatchesNeedingSql(String pluginId, UUID accountId, Pageable pageable) {
        log.debug("Finding batches needing SQL for plugin={}, account={}", pluginId, accountId);

        // Find the account-plugin activation
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(accountId, pluginId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Plugin " + pluginId + " is not activated for account " + accountId));

        // Get set of batch IDs that already have SQL generated
        java.util.Set<UUID> generatedBatchIds = sqlGenerationRepository
                .findGeneratedBatchIdsByAccountPluginId(accountPlugin.getId());

        // Get baseline batch ID
        UUID baselineBatchId = accountPlugin.getBaselineBatchId();

        // Get all completed batches for the account
        Page<Batch> completedBatches = batchRepository.findCompletedByAccountId(accountId, pageable);

        // Filter to only batches without SQL and not baseline
        return completedBatches.stream()
                .filter(batch -> !generatedBatchIds.contains(batch.getId()))
                .filter(batch -> !batch.getId().equals(baselineBatchId))
                .map(batch -> {
                    String siteDomain = siteRepository.findById(batch.getSiteId())
                            .map(Site::getDisplayDomain)
                            .orElse("unknown");

                    return new BatchWithoutSqlDto(
                            batch.getId(),
                            batch.getSiteId(),
                            siteDomain,
                            batch.getStatus().name(),
                            batch.getCompletedAt() != null
                                    ? batch.getCompletedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                                    : null,
                            batch.getUploadedFilesCount(),
                            batch.getTotalSize(),
                            false // not baseline since we filtered
                    );
                })
                .toList();
    }

    // ==================== Batches With SQL Status (User-facing) ====================

    /**
     * Finds all completed batches for an account with their SQL generation status.
     * Returns all batches (with or without SQL) along with their status.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account identifier
     * @param pageable  pagination parameters
     * @return page of batches with SQL status
     */
    public Page<BatchWithSqlStatusDto> findBatchesWithSqlStatus(String pluginId, UUID accountId, Pageable pageable) {
        return findBatchesWithSqlStatus(pluginId, accountId, null, pageable);
    }

    /**
     * Finds completed batches for an account with their SQL generation status,
     * optionally filtered by site.
     *
     * @param pluginId  the plugin identifier
     * @param accountId the account identifier
     * @param siteId    optional site ID filter (null for all sites)
     * @param pageable  pagination parameters
     * @return page of batches with SQL status
     */
    public Page<BatchWithSqlStatusDto> findBatchesWithSqlStatus(String pluginId, UUID accountId, UUID siteId, Pageable pageable) {
        log.debug("Finding batches with SQL status for plugin={}, account={}, siteId={}", pluginId, accountId, siteId);

        // Find the account-plugin activation
        AccountPlugin accountPlugin = accountPluginRepository
                .findByAccountIdAndPluginId(accountId, pluginId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Plugin " + pluginId + " is not activated for account " + accountId));

        // Get SQL generations mapped by batch ID (includes statistics)
        java.util.Map<UUID, PluginSqlGeneration> batchToGenerationMap = sqlGenerationRepository
                .findByAccountPluginId(accountPlugin.getId(), false,
                        org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE))
                .getContent().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PluginSqlGeneration::getSourceBatchId,
                        gen -> gen,
                        (existing, replacement) -> existing // keep first if duplicates
                ));

        // Get baseline batch ID
        UUID baselineBatchId = accountPlugin.getBaselineBatchId();

        // Get completed batches for the account (optionally filtered by site)
        Page<Batch> completedBatches = batchRepository.findCompletedByAccountIdAndOptionalSiteId(
                accountId, siteId, pageable);

        // Map to DTO with SQL status and statistics
        List<BatchWithSqlStatusDto> batchesWithStatus = completedBatches.getContent().stream()
                .map(batch -> {
                    boolean isBaseline = batch.getId().equals(baselineBatchId);
                    PluginSqlGeneration generation = batchToGenerationMap.get(batch.getId());
                    boolean hasSql = generation != null;

                    // Get site domain
                    String siteDomain = siteRepository.findById(batch.getSiteId())
                            .map(Site::getDisplayDomain)
                            .orElse("unknown");

                    if (hasSql) {
                        return BatchWithSqlStatusDto.withSql(
                                batch.getId(),
                                batch.getSiteId(),
                                siteDomain,
                                batch.getStatus().name(),
                                batch.getCompletedAt() != null
                                        ? batch.getCompletedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                                        : null,
                                batch.getUploadedFilesCount(),
                                batch.getTotalSize(),
                                generation.getId(),
                                generation.getInsertCount(),
                                generation.getUpdateCount(),
                                generation.getDeleteCount(),
                                generation.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                        );
                    } else {
                        return BatchWithSqlStatusDto.withoutSql(
                                batch.getId(),
                                batch.getSiteId(),
                                siteDomain,
                                batch.getStatus().name(),
                                batch.getCompletedAt() != null
                                        ? batch.getCompletedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                                        : null,
                                batch.getUploadedFilesCount(),
                                batch.getTotalSize(),
                                isBaseline
                        );
                    }
                })
                .toList();

        return new org.springframework.data.domain.PageImpl<>(
                batchesWithStatus, pageable, completedBatches.getTotalElements());
    }
}
