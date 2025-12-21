package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Summary DTO for an account's plugin integration.
 *
 * <p>Security (FR-012): Plugin-specific data (tenantId, etc.) is NOT exposed.
 * Only metadata (name, activation date, last used) is returned.</p>
 *
 * <p>Used in the GET /api/v1/account/plugins endpoint.</p>
 *
 * @see AccountPluginListResponseDto
 */
@Schema(description = "Summary of an account's plugin integration (excludes sensitive plugin data)")
public record AccountPluginSummaryDto(
    @Schema(description = "Unique plugin identifier", example = "bit-bi")
    String pluginId,

    @Schema(description = "Human-readable plugin name", example = "Bit BI")
    String pluginName,

    @Schema(description = "Current activation status", example = "true")
    boolean isActive,

    @Schema(description = "When the plugin was first activated")
    Instant activatedAt,

    @Schema(description = "When the plugin was deactivated (null if active)", nullable = true)
    Instant deactivatedAt,

    @Schema(description = "When the plugin last received an event", nullable = true)
    Instant lastUsedAt
) {
    /**
     * Creates a summary DTO from an AccountPlugin entity and plugin display name.
     *
     * <p>Note: Does NOT include pluginData field to comply with FR-012 security requirement.</p>
     *
     * @param accountPlugin the activation record
     * @param pluginName the human-readable plugin name from PluginConfig
     * @return summary DTO with metadata only
     */
    public static AccountPluginSummaryDto fromEntity(AccountPlugin accountPlugin, String pluginName) {
        return new AccountPluginSummaryDto(
            accountPlugin.getPluginId(),
            pluginName,
            accountPlugin.isActive(),
            accountPlugin.getActivatedAt(),
            accountPlugin.getDeactivatedAt(),
            accountPlugin.getLastUsedAt()
        );
    }
}
