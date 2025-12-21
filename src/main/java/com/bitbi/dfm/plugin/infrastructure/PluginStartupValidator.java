package com.bitbi.dfm.plugin.infrastructure;

import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates plugin configuration at application startup.
 * Ensures all registered plugins have corresponding database configurations.
 *
 * <p>Per SC-005, plugin discovery must complete within 100ms.</p>
 *
 * <p><b>Production Safety Check:</b> Fails fast if placeholder client IDs are detected
 * in production environments to prevent Auth0 authentication failures.</p>
 */
@Component
public class PluginStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginStartupValidator.class);
    private static final long STARTUP_WARNING_THRESHOLD_MS = 100;
    private static final String PLACEHOLDER_PATTERN = "_PLACEHOLDER";

    private final PluginRegistry pluginRegistry;
    private final PluginConfigRepository pluginConfigRepository;
    private final Environment environment;

    public PluginStartupValidator(
            PluginRegistry pluginRegistry,
            PluginConfigRepository pluginConfigRepository,
            Environment environment) {
        this.pluginRegistry = pluginRegistry;
        this.pluginConfigRepository = pluginConfigRepository;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startTime = System.currentTimeMillis();

        log.info("Starting plugin validation...");

        List<String> warnings = new ArrayList<>();
        List<String> registeredPlugins = new ArrayList<>();
        List<String> placeholderErrors = new ArrayList<>();

        for (Plugin plugin : pluginRegistry.getAll()) {
            String pluginId = plugin.getId();
            registeredPlugins.add(pluginId);

            // Check if plugin has database configuration
            var configOpt = pluginConfigRepository.findByPluginId(pluginId);

            if (configOpt.isEmpty()) {
                warnings.add(String.format(
                    "Plugin '%s' (%s v%s) is registered but has no database configuration. " +
                    "Add a row to plugin_configs table with plugin_id='%s'",
                    plugin.getName(), pluginId, plugin.getVersion(), pluginId));
            } else {
                PluginConfig config = configOpt.get();
                log.debug("Plugin '{}' ({}) validated successfully", plugin.getName(), pluginId);

                // Check for placeholder client IDs
                if (config.getClientId() != null && config.getClientId().contains(PLACEHOLDER_PATTERN)) {
                    placeholderErrors.add(String.format(
                        "Plugin '%s' has placeholder client_id '%s'. " +
                        "Update plugin_configs table with actual Auth0 client_id.",
                        pluginId, config.getClientId()));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Log results
        log.info("Plugin validation complete. Registered plugins: [{}]",
            String.join(", ", registeredPlugins));

        if (!warnings.isEmpty()) {
            warnings.forEach(log::warn);
        }

        // Check for placeholder client IDs - FAIL FAST in production
        if (!placeholderErrors.isEmpty()) {
            boolean isProduction = isProductionEnvironment();

            if (isProduction) {
                placeholderErrors.forEach(log::error);
                throw new IllegalStateException(
                    "CRITICAL: Placeholder client IDs detected in production environment! " +
                    "Update plugin_configs table with actual Auth0 client_ids before deploying to production. " +
                    "Errors: " + String.join("; ", placeholderErrors));
            } else {
                // Warn in non-production environments
                placeholderErrors.forEach(msg ->
                    log.warn("PLACEHOLDER DETECTED (non-prod): {}", msg));
            }
        }

        // Check performance threshold per SC-005
        if (elapsed > STARTUP_WARNING_THRESHOLD_MS) {
            log.warn("Plugin registration exceeded {}ms target: {}ms",
                STARTUP_WARNING_THRESHOLD_MS, elapsed);
        } else {
            log.info("Plugin registration completed in {}ms (target: <{}ms)",
                elapsed, STARTUP_WARNING_THRESHOLD_MS);
        }
    }

    /**
     * Determines if the application is running in a production environment.
     *
     * @return true if 'prod' profile is active
     */
    private boolean isProductionEnvironment() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
