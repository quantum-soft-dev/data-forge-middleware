package com.bitbi.dfm.plugin.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request DTO for plugin activation.
 * Contains plugin-specific data that is validated against the plugin's JSON Schema.
 *
 * <p>Per FR-004, pluginData is validated against the plugin's JSON Schema before activation.</p>
 */
public record ActivatePluginRequestDto(
    @NotNull(message = "pluginData is required")
    Map<String, Object> pluginData
) {
}
