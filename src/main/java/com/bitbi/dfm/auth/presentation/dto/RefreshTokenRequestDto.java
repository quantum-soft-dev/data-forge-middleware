package com.bitbi.dfm.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for token refresh.
 *
 * @param refreshToken the opaque refresh token
 */
@Schema(description = "Request body for refreshing access token")
public record RefreshTokenRequestDto(
        @Schema(description = "Opaque refresh token", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Refresh token is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{43}$", message = "Invalid refresh token format")
        String refreshToken
) {
}
