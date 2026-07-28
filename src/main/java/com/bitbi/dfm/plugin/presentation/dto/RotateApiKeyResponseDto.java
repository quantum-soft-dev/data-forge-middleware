package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginApiKey;

/**
 * Response for the Bit BI API key rotation endpoint (#66).
 * <p>
 * The raw key appears here exactly once — only its BCrypt hash and SHA-256 lookup handle are
 * stored, so it cannot be retrieved again.
 * </p>
 */
public record RotateApiKeyResponseDto(String apiKey) {

    public static RotateApiKeyResponseDto fromApiKey(PluginApiKey apiKey) {
        return new RotateApiKeyResponseDto(apiKey.value());
    }
}
