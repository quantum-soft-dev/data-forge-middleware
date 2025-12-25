package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO for available plugins list in user-facing API.
 *
 * <p>Returns only public fields - no sensitive data like client_id.
 * Used by GET /api/v1/plugins endpoint for regular users to see
 * which plugins they can activate.</p>
 */
@Schema(description = "Available plugin information for activation")
public record AvailablePluginResponseDto(
        @Schema(description = "Unique plugin identifier", example = "bit-bi")
        String pluginId,

        @Schema(description = "Human-readable display name", example = "Bit BI")
        String displayName,

        @Schema(description = "Plugin version", example = "1.0.0")
        String version,

        @Schema(description = "Whether the plugin is enabled for activation")
        boolean isEnabled
) {

    /**
     * Creates a DTO from a PluginConfig entity and Plugin implementation.
     *
     * @param config the database configuration
     * @param plugin the plugin implementation
     * @return the DTO
     */
    public static AvailablePluginResponseDto fromEntity(PluginConfig config, Plugin plugin) {
        return new AvailablePluginResponseDto(
                config.getPluginId(),
                config.getDisplayName(),
                plugin != null ? plugin.getVersion() : "unknown",
                config.isEnabled()
        );
    }
}
