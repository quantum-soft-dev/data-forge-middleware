package com.bitbi.dfm.plugin.domain.exception;

import java.util.List;

/**
 * Exception thrown when plugin data fails JSON Schema validation.
 * Results in HTTP 400 Bad Request.
 *
 * <p>Per FR-004, pluginData must validate against the plugin's JSON Schema.</p>
 */
public class PluginDataValidationException extends RuntimeException {

    private final String pluginId;
    private final List<String> validationErrors;

    public PluginDataValidationException(String pluginId, List<String> validationErrors) {
        super(buildMessage(pluginId, validationErrors));
        this.pluginId = pluginId;
        this.validationErrors = validationErrors;
    }

    public PluginDataValidationException(String pluginId, String error) {
        super("Plugin data validation failed for " + pluginId + ": " + error);
        this.pluginId = pluginId;
        this.validationErrors = List.of(error);
    }

    private static String buildMessage(String pluginId, List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Plugin data validation failed for " + pluginId;
        }
        return "Plugin data validation failed for " + pluginId + ": " + String.join(", ", errors);
    }

    public String getPluginId() {
        return pluginId;
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }
}
