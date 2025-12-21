package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.AccountPlugin;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for plugin activation operations.
 * Contains activation status and metadata.
 *
 * <p>Returned for both new activations (201) and updates (200).</p>
 */
public record PluginActivationResponseDto(
    String pluginId,
    String pluginName,
    UUID accountId,
    boolean isActive,
    Instant activatedAt,
    Instant lastUsedAt
) {
    /**
     * Creates a response DTO from an AccountPlugin entity and plugin display name.
     *
     * @param accountPlugin the activation record
     * @param pluginName the human-readable plugin name
     * @return response DTO
     */
    public static PluginActivationResponseDto fromEntity(AccountPlugin accountPlugin, String pluginName) {
        return new PluginActivationResponseDto(
            accountPlugin.getPluginId(),
            pluginName,
            accountPlugin.getAccountId(),
            accountPlugin.isActive(),
            accountPlugin.getActivatedAt(),
            accountPlugin.getLastUsedAt()
        );
    }
}
