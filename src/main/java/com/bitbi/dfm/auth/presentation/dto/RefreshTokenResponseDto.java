package com.bitbi.dfm.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Response DTO for token refresh.
 *
 * @param accessToken           new JWT access token
 * @param refreshToken          new rotated refresh token
 * @param accessTokenExpiresAt  access token expiration
 * @param refreshTokenExpiresAt refresh token expiration
 */
@Schema(description = "Response with new access token and rotated refresh token")
public record RefreshTokenResponseDto(
        @Schema(description = "New JWT access token")
        @JsonProperty("accessToken")
        String accessToken,

        @Schema(description = "New rotated refresh token (old token is now invalid)")
        @JsonProperty("refreshToken")
        String refreshToken,

        @Schema(description = "Access token expiration timestamp")
        @JsonProperty("accessTokenExpiresAt")
        Instant accessTokenExpiresAt,

        @Schema(description = "Refresh token expiration timestamp")
        @JsonProperty("refreshTokenExpiresAt")
        Instant refreshTokenExpiresAt
) {
}
