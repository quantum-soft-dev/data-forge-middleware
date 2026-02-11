package com.bitbi.dfm.deviceauth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO with tokens after approval (Auth V2).
 *
 * @param siteId                 created site ID
 * @param siteName               site display name
 * @param accessToken            JWT access token (1 hour TTL)
 * @param refreshToken           opaque refresh token (90 days TTL)
 * @param accessTokenExpiresAt   access token expiration
 * @param refreshTokenExpiresAt  refresh token expiration
 * @param apiBaseUrl             API base URL for subsequent requests
 * @author Data Forge Team
 * @version 2.0.0
 */
@Schema(description = "Tokens after successful device authorization (Auth V2)")
public record DeviceTokenResponseDto(

        @Schema(description = "Created site ID", example = "550e8400-e29b-41d4-a716-446655440000")
        @JsonProperty("siteId")
        UUID siteId,

        @Schema(description = "Site name", example = "warehouse-01")
        @JsonProperty("siteName")
        String siteName,

        @Schema(description = "JWT access token")
        @JsonProperty("accessToken")
        String accessToken,

        @Schema(description = "Opaque refresh token (store securely!)")
        @JsonProperty("refreshToken")
        String refreshToken,

        @Schema(description = "Access token expiration timestamp")
        @JsonProperty("accessTokenExpiresAt")
        Instant accessTokenExpiresAt,

        @Schema(description = "Refresh token expiration timestamp")
        @JsonProperty("refreshTokenExpiresAt")
        Instant refreshTokenExpiresAt,

        @Schema(description = "API base URL", example = "https://api.dataforge.com")
        @JsonProperty("apiBaseUrl")
        String apiBaseUrl
) {
    /**
     * Create response from service result.
     */
    public static DeviceTokenResponseDto fromResult(
            com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService.TokenResult.Success success) {
        return new DeviceTokenResponseDto(
                success.siteId(),
                success.siteName(),
                success.accessToken(),
                success.refreshToken(),
                success.accessTokenExpiresAt(),
                success.refreshTokenExpiresAt(),
                success.apiBaseUrl()
        );
    }
}
