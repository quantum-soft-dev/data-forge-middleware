package com.bitbi.dfm.plugin.domain;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for PluginConfig entities.
 */
public interface PluginConfigRepository {

    /**
     * Finds a plugin configuration by its plugin ID.
     * @param pluginId the plugin identifier
     * @return the plugin configuration if found
     */
    Optional<PluginConfig> findByPluginId(String pluginId);

    /**
     * Finds a plugin configuration by its Auth0 client ID.
     * @param clientId the Auth0 client ID
     * @return the plugin configuration if found
     */
    Optional<PluginConfig> findByClientId(String clientId);

    /**
     * Returns all enabled plugin configurations.
     * @return list of enabled plugins
     */
    List<PluginConfig> findAllEnabled();

    /**
     * Returns all plugin configurations.
     * @return list of all plugins
     */
    List<PluginConfig> findAll();

    /**
     * Checks if a plugin with the given ID exists and is enabled.
     * @param pluginId the plugin identifier
     * @return true if plugin exists and is enabled
     */
    boolean existsByPluginIdAndEnabledTrue(String pluginId);

    /**
     * Saves a plugin configuration.
     * @param pluginConfig the configuration to save
     * @return the saved configuration
     */
    PluginConfig save(PluginConfig pluginConfig);
}
