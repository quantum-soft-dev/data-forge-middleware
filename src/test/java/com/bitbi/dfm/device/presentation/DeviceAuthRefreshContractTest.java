package com.bitbi.dfm.device.presentation;

import com.bitbi.dfm.auth.application.RefreshTokenService;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for POST /api/v1/device/auth/refresh (Auth V2).
 * <p>
 * Verifies the refresh token endpoint returns correct HTTP statuses
 * and response structures for various scenarios.
 * </p>
 */
@DisplayName("Device API - Token Refresh Contract Tests")
class DeviceAuthRefreshContractTest extends BaseIntegrationTest {

    /**
     * Site ID for store-03 from test-data.sql (used in Device API tests).
     */
    private static final UUID STORE_03_SITE_ID =
            UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("Should refresh token successfully and return new access + refresh tokens")
    void shouldRefreshTokenSuccessfully() throws Exception {
        // Given: A valid refresh token for an active site
        String refreshToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);

        String requestBody = """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);

        // When/Then: POST /api/v1/device/auth/refresh → 200 with new tokens
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
                .andExpect(jsonPath("$.refreshTokenExpiresAt").exists());
    }

    @Test
    @DisplayName("Should return 400 for malformed refresh token (wrong format)")
    void shouldReturn400ForMalformedRefreshToken() throws Exception {
        String requestBody = """
                {"refreshToken": "completely-invalid-token"}
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for unknown but well-formed refresh token")
    void shouldReturn400ForUnknownRefreshToken() throws Exception {
        // A valid 43-char Base64url token that doesn't exist in the database
        String fakeToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq";

        String requestBody = """
                {"refreshToken": "%s"}
                """.formatted(fakeToken);

        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_refresh_token"));
    }

    @Test
    @DisplayName("Should return 401 for revoked refresh token")
    void shouldReturn401ForRevokedRefreshToken() throws Exception {
        // Given: Generate and then revoke a token
        String refreshToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        refreshTokenService.revokeRefreshToken(refreshToken);

        String requestBody = """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);

        // When/Then: 401 with refresh_token_revoked error
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));
    }

    @Test
    @DisplayName("Should return 400 for empty refresh token")
    void shouldReturn400ForEmptyRefreshToken() throws Exception {
        String requestBody = """
                {"refreshToken": ""}
                """;

        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should rotate token - old token invalid after refresh")
    void shouldRotateTokenOldTokenInvalidAfterRefresh() throws Exception {
        // Given: A valid refresh token
        String originalToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);

        // When: Use it to refresh (consumes the token)
        String requestBody = """
                {"refreshToken": "%s"}
                """.formatted(originalToken);

        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        // Then: Reusing the same token should fail (revoked after rotation)
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));
    }

    @Test
    @DisplayName("Should not require Bearer token (endpoint is public)")
    void shouldNotRequireBearerToken() throws Exception {
        // Given: A valid refresh token, no Authorization header
        String refreshToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);

        String requestBody = """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);

        // When/Then: Should succeed without Authorization header
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());
    }
}
