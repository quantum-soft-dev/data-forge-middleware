package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.Plugin;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginEventType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DTO for registered plugin information in admin API responses.
 *
 * <p>Combines data from PluginConfig (database) and Plugin (code) to provide
 * complete plugin information for administrators.</p>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Schema(description = "Registered plugin configuration")
public record PluginConfigResponseDto(
        @Schema(description = "Unique plugin identifier", example = "bit-bi")
        String pluginId,

        @Schema(description = "Human-readable display name", example = "Bit BI")
        String displayName,

        @Schema(description = "Plugin version", example = "1.0.0")
        String version,

        @Schema(description = "Whether the plugin is enabled for new activations")
        boolean isEnabled,

        @Schema(description = "Event types this plugin supports",
                example = "[\"BATCH_COMPLETED\", \"BATCH_FAILED\"]")
        Set<String> supportedEvents,

        @Schema(description = "When the plugin configuration was created")
        Instant createdAt,

        @Schema(description = "When the plugin configuration was last updated")
        Instant updatedAt
) {

    /**
     * Creates a DTO from a PluginConfig entity and Plugin implementation.
     *
     * @param config the database configuration
     * @param plugin the plugin implementation
     * @return the DTO
     */
    public static PluginConfigResponseDto fromEntity(PluginConfig config, Plugin plugin) {
        Set<String> eventNames = plugin.getSupportedEvents().stream()
                .map(PluginEventType::name)
                .collect(Collectors.toSet());

        return new PluginConfigResponseDto(
                config.getPluginId(),
                config.getDisplayName(),
                plugin.getVersion(),
                config.isEnabled(),
                eventNames,
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    /**
     * Creates a DTO from a PluginConfig entity only (when plugin implementation is not available).
     *
     * @param config the database configuration
     * @return the DTO with limited information
     */
    public static PluginConfigResponseDto fromEntityOnly(PluginConfig config) {
        return new PluginConfigResponseDto(
                config.getPluginId(),
                config.getDisplayName(),
                "unknown", // Version unavailable without plugin implementation
                config.isEnabled(),
                Set.of(), // Events unavailable without plugin implementation
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }
}
