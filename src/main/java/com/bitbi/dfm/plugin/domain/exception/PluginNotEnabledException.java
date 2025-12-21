package com.bitbi.dfm.plugin.domain.exception;

/**
 * Exception thrown when a plugin exists but is not enabled.
 * Results in HTTP 404 Not Found (plugin is effectively unavailable).
 *
 * <p>Per business rules, disabled plugins cannot accept new activations
 * but existing activations remain active.</p>
 */
public class PluginNotEnabledException extends RuntimeException {

    private final String pluginId;

    public PluginNotEnabledException(String pluginId) {
        super("Plugin is not enabled: " + pluginId);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
