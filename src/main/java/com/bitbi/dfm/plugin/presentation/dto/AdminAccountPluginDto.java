package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin DTO for account-plugin listing.
 *
 * <p>Includes accountId for admin operations like viewing SQL history.
 * Security: Does NOT expose plugin-specific data (tenantId, apiKey, etc.).</p>
 *
 * <p>Feature 014: Plugin History Management</p>
 */
@Schema(description = "Admin view of account-plugin activation")
public record AdminAccountPluginDto(
    @Schema(description = "Account ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    UUID accountId,

    @Schema(description = "Plugin identifier", example = "bit-bi")
    String pluginId,

    @Schema(description = "Plugin display name", example = "Bit BI")
    String pluginName,

    @Schema(description = "Current activation status")
    boolean isActive,

    @Schema(description = "When the plugin was activated")
    Instant activatedAt,

    @Schema(description = "When the plugin was deactivated (null if active)")
    Instant deactivatedAt,

    @Schema(description = "When the plugin last processed an event")
    Instant lastUsedAt,

    @Schema(description = "Number of SQL generations for this account-plugin")
    long generationCount
) {
    /**
     * Creates an admin DTO from entity with generation count.
     */
    public static AdminAccountPluginDto fromEntity(
            AccountPlugin accountPlugin,
            String pluginName,
            long generationCount
    ) {
        return new AdminAccountPluginDto(
            accountPlugin.getAccountId(),
            accountPlugin.getPluginId(),
            pluginName,
            accountPlugin.isActive(),
            accountPlugin.getActivatedAt(),
            accountPlugin.getDeactivatedAt(),
            accountPlugin.getLastUsedAt(),
            generationCount
        );
    }
}
