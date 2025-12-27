package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.AccountPlugin;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for plugin activation operations.
 * Contains activation status and metadata.
 *
 * <p>Returned for both new activations (201) and updates (200).</p>
 *
 * <p>Per FR-019, the apiKey field is only populated for new activations.
 * This is the only time the raw API key is shown to the user - it's stored
 * as a BCrypt hash and cannot be retrieved later.</p>
 */
public record PluginActivationResponseDto(
    String pluginId,
    String pluginName,
    UUID accountId,
    boolean isActive,
    Instant activatedAt,
    Instant lastUsedAt,
    String apiKey
) {
    /**
     * Creates a response DTO from an AccountPlugin entity, plugin display name, and optional API key.
     *
     * @param accountPlugin the activation record
     * @param pluginName the human-readable plugin name
     * @param apiKey the raw API key (only for new activations, null otherwise)
     * @return response DTO
     */
    public static PluginActivationResponseDto fromEntity(AccountPlugin accountPlugin, String pluginName, String apiKey) {
        return new PluginActivationResponseDto(
            accountPlugin.getPluginId(),
            pluginName,
            accountPlugin.getAccountId(),
            accountPlugin.isActive(),
            accountPlugin.getActivatedAt(),
            accountPlugin.getLastUsedAt(),
            apiKey
        );
    }
}
