package com.bitbi.dfm.plugin.domain.exception;

/**
 * Exception thrown when a plugin is not found in the registry or database.
 * Results in HTTP 404 Not Found.
 */
public class PluginNotFoundException extends RuntimeException {

    private final String pluginId;

    public PluginNotFoundException(String pluginId) {
        super("Plugin not found: " + pluginId);
        this.pluginId = pluginId;
    }

    public PluginNotFoundException(String pluginId, String message) {
        super(message);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }
}
