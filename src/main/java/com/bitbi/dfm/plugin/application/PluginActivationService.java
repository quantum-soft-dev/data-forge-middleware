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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    private final MeterRegistry meterRegistry;

    // Metrics
    private final Timer activationTimer;
    private final Timer deactivationTimer;
    private final Counter activationCounter;
    private final Counter deactivationCounter;

    public PluginActivationService(
            PluginRegistry pluginRegistry,
            PluginConfigRepository pluginConfigRepository,
            AccountPluginRepository accountPluginRepository,
            PluginDataValidator pluginDataValidator,
            MeterRegistry meterRegistry) {
        this.pluginRegistry = pluginRegistry;
        this.pluginConfigRepository = pluginConfigRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.pluginDataValidator = pluginDataValidator;
        this.meterRegistry = meterRegistry;

        // Initialize metrics
        this.activationTimer = Timer.builder("plugin.activation.duration")
                .description("Time taken to activate a plugin")
                .register(meterRegistry);
        this.deactivationTimer = Timer.builder("plugin.deactivation.duration")
                .description("Time taken to deactivate a plugin")
                .register(meterRegistry);
        this.activationCounter = Counter.builder("plugin.activation.count")
                .description("Number of plugin activations")
                .register(meterRegistry);
        this.deactivationCounter = Counter.builder("plugin.deactivation.count")
                .description("Number of plugin deactivations")
                .register(meterRegistry);
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
        // Set MDC context for structured logging
        MDC.put("pluginId", pluginId);
        MDC.put("accountId", accountId.toString());

        try {
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
            boolean isReactivation = false;

            if (existingActivation.isPresent()) {
                accountPlugin = existingActivation.get();
                if (accountPlugin.isActive()) {
                    // Already active - update data
                    logger.debug("Updating existing active plugin {} for account {}", pluginId, accountId);
                    accountPlugin.updatePluginData(pluginData);
                    isNewActivation = false;
                } else {
                    // Was deactivated - reactivate (new API key will be generated)
                    logger.debug("Reactivating plugin {} for account {}", pluginId, accountId);
                    accountPlugin.reactivate(pluginData);
                    isNewActivation = false;
                    isReactivation = true;
                }
            } else {
                // New activation
                logger.debug("Creating new activation for plugin {} for account {}", pluginId, accountId);
                accountPlugin = AccountPlugin.activate(accountId, pluginId, pluginData);
                isNewActivation = true;
            }

            // 5. Save activation record with race condition handling
            try {
                accountPlugin = accountPluginRepository.save(accountPlugin);
            } catch (DataIntegrityViolationException e) {
                // Race condition: another request created the activation between find and save
                // Retry by fetching the existing record and updating it
                logger.info("Race condition detected for plugin {} / account {} - retrying with existing record",
                        pluginId, accountId);

                AccountPlugin existingPlugin = accountPluginRepository
                        .findByAccountIdAndPluginId(accountId, pluginId)
                        .orElseThrow(() -> {
                            // Should not happen - constraint violation implies record exists
                            logger.error("Unexpected state: DataIntegrityViolation but no record found for {} / {}",
                                    pluginId, accountId);
                            return e;
                        });

                // Update the existing record with merge semantics
                if (existingPlugin.isActive()) {
                    existingPlugin.updatePluginData(pluginData);
                } else {
                    existingPlugin.reactivate(pluginData);
                    isReactivation = true;
                }
                accountPlugin = accountPluginRepository.save(existingPlugin);
                isNewActivation = false;
            }

            // 6. Call lifecycle hook (FR-006, FR-019)
            String apiKey = null;
            try {
                apiKey = plugin.onActivate(accountPlugin);
                logger.debug("Plugin {} onActivate hook completed for account {}", pluginId, accountId);
            } catch (Exception e) {
                // Log but don't fail the activation - hook failures are non-critical
                logger.warn("Plugin {} onActivate hook failed for account {}: {}", pluginId, accountId, e.getMessage());
            }

            // 7. Trigger async SQL initialization for new activations and reactivations (FR-001, FR-002)
            boolean shouldInitializeSql = isNewActivation || isReactivation;
            if (plugin instanceof BitBiPlugin bitBiPlugin) {
                bitBiPlugin.initializeSqlFromLatestBatch(accountPlugin, shouldInitializeSql);
                logger.debug("SQL initialization triggered for plugin {} account {} (newOrReactivation={})",
                        pluginId, accountId, shouldInitializeSql);
            }

            // Return API key for new activations and reactivations (new key is generated in both cases)
            // Don't expose on updates - the existing key remains valid
            String returnApiKey = (isNewActivation || isReactivation) ? apiKey : null;

            String action = isNewActivation ? "activated" : (isReactivation ? "reactivated" : "updated");
            logger.info("Plugin {} {} for account {}", pluginId, action, accountId);

            // 7. Record metrics
            // Note: Audit logging is handled by PluginAuditFilter for HTTP requests
            long duration = System.currentTimeMillis() - startTime;
            activationTimer.record(duration, TimeUnit.MILLISECONDS);
            activationCounter.increment();

            return new ActivationResult(accountPlugin, pluginConfig.getDisplayName(), isNewActivation, returnApiKey);
        } finally {
            // Clear MDC context
            MDC.remove("pluginId");
            MDC.remove("accountId");
        }
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
        // Set MDC context for structured logging
        MDC.put("pluginId", pluginId);
        MDC.put("accountId", accountId.toString());

        try {
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

            // 7. Record metrics
            // Note: Audit logging is handled by PluginAuditFilter for HTTP requests
            long duration = System.currentTimeMillis() - startTime;
            deactivationTimer.record(duration, TimeUnit.MILLISECONDS);
            deactivationCounter.increment();
        } finally {
            // Clear MDC context
            MDC.remove("pluginId");
            MDC.remove("accountId");
        }
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
     * @param apiKey raw API key if generated (only for new activations), null otherwise
     */
    public record ActivationResult(
        AccountPlugin accountPlugin,
        String pluginDisplayName,
        boolean isNewActivation,
        String apiKey
    ) {}
}
