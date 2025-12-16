package com.bitbi.dfm.plugin.infrastructure;

import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import com.bitbi.dfm.plugin.domain.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates plugin configuration at application startup.
 * Ensures all registered plugins have corresponding database configurations.
 *
 * <p>Per SC-005, plugin discovery must complete within 100ms.</p>
 */
@Component
public class PluginStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginStartupValidator.class);
    private static final long STARTUP_WARNING_THRESHOLD_MS = 100;

    private final PluginRegistry pluginRegistry;
    private final PluginConfigRepository pluginConfigRepository;

    public PluginStartupValidator(
            PluginRegistry pluginRegistry,
            PluginConfigRepository pluginConfigRepository) {
        this.pluginRegistry = pluginRegistry;
        this.pluginConfigRepository = pluginConfigRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startTime = System.currentTimeMillis();

        log.info("Starting plugin validation...");

        List<String> warnings = new ArrayList<>();
        List<String> registeredPlugins = new ArrayList<>();

        for (Plugin plugin : pluginRegistry.getAll()) {
            String pluginId = plugin.getId();
            registeredPlugins.add(pluginId);

            // Check if plugin has database configuration
            boolean hasConfig = pluginConfigRepository.findByPluginId(pluginId).isPresent();

            if (!hasConfig) {
                warnings.add(String.format(
                    "Plugin '%s' (%s v%s) is registered but has no database configuration. " +
                    "Add a row to plugin_configs table with plugin_id='%s'",
                    plugin.getName(), pluginId, plugin.getVersion(), pluginId));
            } else {
                log.debug("Plugin '{}' ({}) validated successfully", plugin.getName(), pluginId);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Log results
        log.info("Plugin validation complete. Registered plugins: [{}]",
            String.join(", ", registeredPlugins));

        if (!warnings.isEmpty()) {
            warnings.forEach(log::warn);
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
}
