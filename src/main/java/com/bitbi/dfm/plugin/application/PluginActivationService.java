package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import com.bitbi.dfm.plugin.domain.exception.PluginNotActivatedException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotEnabledException;
import com.bitbi.dfm.plugin.domain.exception.PluginNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for plugin activation and deactivation operations.
 *
 * <p>Handles the activation workflow per FR-005 (upsert behavior) and FR-006 (lifecycle hooks):
 * <ul>
 *   <li>New activation: Creates AccountPlugin record, calls plugin.onActivate()</li>
 *   <li>Update (already active): Updates pluginData, calls plugin.onActivate()</li>
 *   <li>Reactivation (was inactive): Reactivates with new data, calls plugin.onActivate()</li>
 * </ul>
 */
@Service
@Transactional
public class PluginActivationService {

    private static final Logger logger = LoggerFactory.getLogger(PluginActivationService.class);

    private final PluginRegistry pluginRegistry;
    private final PluginConfigRepository pluginConfigRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final PluginDataValidator pluginDataValidator;
    private final PluginAuditService pluginAuditService;

    public PluginActivationService(
            PluginRegistry pluginRegistry,
            PluginConfigRepository pluginConfigRepository,
            AccountPluginRepository accountPluginRepository,
            PluginDataValidator pluginDataValidator,
            PluginAuditService pluginAuditService) {
        this.pluginRegistry = pluginRegistry;
        this.pluginConfigRepository = pluginConfigRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.pluginDataValidator = pluginDataValidator;
        this.pluginAuditService = pluginAuditService;
    }

    /**
     * Activates a plugin for an account.
     *
     * <p>Implements upsert behavior per FR-005:
     * <ul>
     *   <li>If not activated: Creates new activation record</li>
     *   <li>If already active: Updates pluginData and timestamps</li>
     *   <li>If deactivated: Reactivates with new pluginData</li>
     * </ul>
     *
     * @param accountId the account to activate the plugin for
     * @param pluginId the plugin identifier
     * @param pluginData plugin-specific data (validated against schema)
     * @return result containing the activation record and whether it was newly created
     */
    public ActivationResult activate(UUID accountId, String pluginId, Map<String, Object> pluginData) {
        return activate(accountId, pluginId, pluginData, null);
    }

    /**
     * Activates a plugin for an account with optional client ID for audit logging.
     *
     * @param accountId the account to activate the plugin for
     * @param pluginId the plugin identifier
     * @param pluginData plugin-specific data (validated against schema)
     * @param clientId the OAuth client ID for audit logging (optional)
     * @return result containing the activation record and whether it was newly created
     */
    public ActivationResult activate(UUID accountId, String pluginId, Map<String, Object> pluginData, @Nullable String clientId) {
        logger.info("Activating plugin {} for account {}", pluginId, accountId);
        long startTime = System.currentTimeMillis();

        // 1. Verify plugin is registered in code
        Plugin plugin = pluginRegistry.findById(pluginId)
            .orElseThrow(() -> {
                logger.warn("Plugin not found in registry: {}", pluginId);
                return new PluginNotFoundException(pluginId);
            });

        // 2. Verify plugin is configured and enabled in database
        PluginConfig pluginConfig = pluginConfigRepository.findByPluginId(pluginId)
            .orElseThrow(() -> {
                logger.warn("Plugin not found in database: {}", pluginId);
                return new PluginNotFoundException(pluginId, "Plugin is not configured: " + pluginId);
            });

        if (!pluginConfig.isEnabled()) {
            logger.warn("Plugin is disabled: {}", pluginId);
            throw new PluginNotEnabledException(pluginId);
        }

        // 3. Validate pluginData against schema (FR-004)
        pluginDataValidator.validate(pluginId, pluginData);

        // 4. Check for existing activation (upsert behavior per FR-005)
        Optional<AccountPlugin> existingActivation = accountPluginRepository
            .findByAccountIdAndPluginId(accountId, pluginId);

        AccountPlugin accountPlugin;
        boolean isNewActivation;

        if (existingActivation.isPresent()) {
            accountPlugin = existingActivation.get();
            if (accountPlugin.isActive()) {
                // Already active - update data
                logger.debug("Updating existing active plugin {} for account {}", pluginId, accountId);
                accountPlugin.updatePluginData(pluginData);
                isNewActivation = false;
            } else {
                // Was deactivated - reactivate
                logger.debug("Reactivating plugin {} for account {}", pluginId, accountId);
                accountPlugin.reactivate(pluginData);
                isNewActivation = false;
            }
        } else {
            // New activation
            logger.debug("Creating new activation for plugin {} for account {}", pluginId, accountId);
            accountPlugin = AccountPlugin.activate(accountId, pluginId, pluginData);
            isNewActivation = true;
        }

        // 5. Save activation record
        accountPlugin = accountPluginRepository.save(accountPlugin);

        // 6. Call lifecycle hook (FR-006)
        try {
            plugin.onActivate(accountPlugin);
            logger.debug("Plugin {} onActivate hook completed for account {}", pluginId, accountId);
        } catch (Exception e) {
            // Log but don't fail the activation - hook failures are non-critical
            logger.warn("Plugin {} onActivate hook failed for account {}: {}", pluginId, accountId, e.getMessage());
        }

        logger.info("Plugin {} {} for account {}", pluginId,
            isNewActivation ? "activated" : "updated", accountId);

        // 7. Log audit entry (async, non-blocking)
        long duration = System.currentTimeMillis() - startTime;
        if (isNewActivation) {
            pluginAuditService.logActivation(pluginId, accountId, clientId, duration);
        } else {
            pluginAuditService.logReactivation(pluginId, accountId, clientId, duration);
        }

        return new ActivationResult(accountPlugin, pluginConfig.getDisplayName(), isNewActivation);
    }

    /**
     * Deactivates a plugin for an account.
     *
     * <p>Sets is_active=false, records deactivated_at timestamp, and calls
     * the plugin's onDeactivate() lifecycle hook per FR-006.</p>
     *
     * @param accountId the account to deactivate the plugin for
     * @param pluginId the plugin identifier
     * @throws PluginNotFoundException if plugin is not registered
     * @throws PluginNotActivatedException if plugin is not active for this account
     */
    public void deactivate(UUID accountId, String pluginId) {
        deactivate(accountId, pluginId, null);
    }

    /**
     * Deactivates a plugin for an account with optional client ID for audit logging.
     *
     * @param accountId the account to deactivate the plugin for
     * @param pluginId the plugin identifier
     * @param clientId the OAuth client ID for audit logging (optional)
     * @throws PluginNotFoundException if plugin is not registered
     * @throws PluginNotActivatedException if plugin is not active for this account
     */
    public void deactivate(UUID accountId, String pluginId, @Nullable String clientId) {
        logger.info("Deactivating plugin {} for account {}", pluginId, accountId);
        long startTime = System.currentTimeMillis();

        // 1. Verify plugin is registered in code
        Plugin plugin = pluginRegistry.findById(pluginId)
            .orElseThrow(() -> {
                logger.warn("Plugin not found in registry: {}", pluginId);
                return new PluginNotFoundException(pluginId);
            });

        // 2. Find existing activation
        AccountPlugin accountPlugin = accountPluginRepository
            .findByAccountIdAndPluginId(accountId, pluginId)
            .orElseThrow(() -> {
                logger.warn("No activation found for plugin {} account {}", pluginId, accountId);
                return new PluginNotActivatedException(pluginId, accountId);
            });

        // 3. Check if already deactivated
        if (!accountPlugin.isActive()) {
            logger.warn("Plugin {} already deactivated for account {}", pluginId, accountId);
            throw new PluginNotActivatedException(pluginId, accountId,
                "Plugin '" + pluginId + "' is already deactivated for account " + accountId);
        }

        // 4. Deactivate the plugin
        accountPlugin.deactivate();

        // 5. Save the deactivated record
        accountPluginRepository.save(accountPlugin);

        // 6. Call lifecycle hook (FR-006)
        try {
            plugin.onDeactivate(accountPlugin);
            logger.debug("Plugin {} onDeactivate hook completed for account {}", pluginId, accountId);
        } catch (Exception e) {
            // Log but don't fail the deactivation - hook failures are non-critical
            logger.warn("Plugin {} onDeactivate hook failed for account {}: {}", pluginId, accountId, e.getMessage());
        }

        logger.info("Plugin {} deactivated for account {}", pluginId, accountId);

        // 7. Log audit entry (async, non-blocking)
        long duration = System.currentTimeMillis() - startTime;
        pluginAuditService.logDeactivation(pluginId, accountId, clientId, duration);
    }

    /**
     * Gets the display name for a plugin.
     *
     * @param pluginId the plugin identifier
     * @return the display name
     */
    public String getPluginDisplayName(String pluginId) {
        return pluginConfigRepository.findByPluginId(pluginId)
            .map(PluginConfig::getDisplayName)
            .orElseGet(() -> pluginRegistry.findById(pluginId)
                .map(Plugin::getName)
                .orElse(pluginId));
    }

    /**
     * Result of an activation operation.
     *
     * @param accountPlugin the activation record
     * @param pluginDisplayName the human-readable plugin name
     * @param isNewActivation true if this was a new activation, false if update/reactivation
     */
    public record ActivationResult(
        AccountPlugin accountPlugin,
        String pluginDisplayName,
        boolean isNewActivation
    ) {}
}
