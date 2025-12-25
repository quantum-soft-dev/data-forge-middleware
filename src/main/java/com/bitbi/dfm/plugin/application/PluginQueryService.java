package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import com.bitbi.dfm.plugin.presentation.dto.AccountPluginListResponseDto;
import com.bitbi.dfm.plugin.presentation.dto.AccountPluginSummaryDto;
import com.bitbi.dfm.plugin.presentation.dto.AvailablePluginResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Query service for plugin-related read operations.
 *
 * <p>Implements the read-side of the plugin domain, providing paginated queries
 * for account plugin integrations with proper security filtering.</p>
 *
 * <p>User Story 4 (Phase 6) implementation - View Active Plugin Integrations:</p>
 * <ul>
 *   <li>FR-011: List active plugin integrations for an account</li>
 *   <li>FR-012: Do NOT expose pluginData (tenantId, etc.) in responses</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PluginQueryService {

    private static final Logger log = LoggerFactory.getLogger(PluginQueryService.class);

    private final AccountPluginRepository accountPluginRepository;
    private final PluginConfigRepository pluginConfigRepository;
    private final PluginRegistry pluginRegistry;

    public PluginQueryService(
            AccountPluginRepository accountPluginRepository,
            PluginConfigRepository pluginConfigRepository,
            PluginRegistry pluginRegistry) {
        this.accountPluginRepository = accountPluginRepository;
        this.pluginConfigRepository = pluginConfigRepository;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * Lists plugin integrations for an account with pagination.
     *
     * <p>Returns metadata only (FR-012) - plugin-specific data (tenantId, etc.)
     * is NOT included in the response for security reasons.</p>
     *
     * @param accountId the account identifier from JWT
     * @param includeInactive whether to include deactivated plugins
     * @param pageable pagination parameters
     * @return paginated list of plugin summaries
     */
    public AccountPluginListResponseDto listAccountPlugins(
            UUID accountId,
            boolean includeInactive,
            Pageable pageable) {

        log.debug("Listing plugins for account {}, includeInactive={}", accountId, includeInactive);

        // Fetch paginated account plugins
        Page<AccountPlugin> accountPluginsPage = accountPluginRepository.findByAccountId(
                accountId, includeInactive, pageable);

        // Pre-fetch all plugin configs for display names (avoid N+1)
        List<PluginConfig> allConfigs = pluginConfigRepository.findAll();
        Map<String, String> pluginDisplayNames = allConfigs.stream()
                .collect(Collectors.toMap(
                        PluginConfig::getPluginId,
                        PluginConfig::getDisplayName
                ));

        // Map to DTOs
        List<AccountPluginSummaryDto> content = accountPluginsPage.getContent().stream()
                .map(ap -> AccountPluginSummaryDto.fromEntity(
                        ap,
                        pluginDisplayNames.getOrDefault(ap.getPluginId(), ap.getPluginId())
                ))
                .toList();

        log.debug("Found {} plugins for account {} (page {} of {})",
                content.size(), accountId, pageable.getPageNumber(), accountPluginsPage.getTotalPages());

        return AccountPluginListResponseDto.of(
                content,
                accountPluginsPage.getNumber(),
                accountPluginsPage.getSize(),
                accountPluginsPage.getTotalElements(),
                accountPluginsPage.getTotalPages()
        );
    }

    /**
     * Lists all available (enabled) plugins for activation.
     *
     * <p>Returns public information only - no sensitive data like client_id.
     * Used by GET /api/v1/plugins endpoint for regular users.</p>
     *
     * @return list of available plugins
     */
    public List<AvailablePluginResponseDto> listAvailablePlugins() {
        log.debug("Listing available plugins");

        List<PluginConfig> enabledConfigs = pluginConfigRepository.findAllEnabled();

        List<AvailablePluginResponseDto> result = enabledConfigs.stream()
                .map(config -> AvailablePluginResponseDto.fromEntity(
                        config,
                        pluginRegistry.findById(config.getPluginId()).orElse(null)
                ))
                .toList();

        log.debug("Found {} available plugins", result.size());
        return result;
    }
}
