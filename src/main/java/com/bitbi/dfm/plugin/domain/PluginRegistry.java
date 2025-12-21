package com.bitbi.dfm.plugin.domain;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory registry of discovered plugins.
 * Collects all Plugin beans via constructor injection at startup.
 *
 * <p>This is the central point for looking up registered plugins by ID.</p>
 */
@Component
public class PluginRegistry {

    private final Map<String, Plugin> plugins;

    /**
     * Creates a new PluginRegistry with all discovered Plugin beans.
     * @param pluginBeans list of Plugin implementations (auto-injected by Spring)
     */
    public PluginRegistry(List<Plugin> pluginBeans) {
        this.plugins = pluginBeans != null
            ? pluginBeans.stream().collect(Collectors.toMap(Plugin::getId, Function.identity()))
            : Collections.emptyMap();
    }

    /**
     * Finds a plugin by its ID.
     * @param pluginId the plugin identifier
     * @return the plugin if found
     */
    public Optional<Plugin> findById(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    /**
     * Returns all registered plugins.
     * @return unmodifiable collection of all plugins
     */
    public Collection<Plugin> getAll() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    /**
     * Returns all registered plugin IDs.
     * @return unmodifiable set of plugin IDs
     */
    public Set<String> getAllIds() {
        return Collections.unmodifiableSet(plugins.keySet());
    }

    /**
     * Checks if a plugin with the given ID is registered.
     * @param pluginId the plugin identifier
     * @return true if registered
     */
    public boolean isRegistered(String pluginId) {
        return plugins.containsKey(pluginId);
    }

    /**
     * Returns the count of registered plugins.
     * @return number of registered plugins
     */
    public int size() {
        return plugins.size();
    }

    /**
     * Finds all plugins that support a specific event type.
     * @param eventType the event type to filter by
     * @return list of plugins supporting this event type
     */
    public List<Plugin> findBySupportedEvent(PluginEventType eventType) {
        return plugins.values().stream()
            .filter(plugin -> plugin.getSupportedEvents().contains(eventType))
            .toList();
    }
}
