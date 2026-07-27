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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Build a refresh request for the given opaque token.
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refreshRequest(String token) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("refreshToken", token));
        return post(ApiRoutes.DEVICE_AUTH_REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * Perform a successful refresh and return the rotated refresh token.
     */
    private String refreshFor(String token) throws Exception {
        String responseBody = mockMvc.perform(refreshRequest(token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(responseBody).get("refreshToken").asText();
    }

    /**
     * Force a stored refresh token past its expiry (tokens have a 90-day TTL by default).
     */
    private void expireToken(String opaqueToken) throws Exception {
        int updated = jdbcTemplate.update(
                "UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                sha256Hex(opaqueToken));
        org.assertj.core.api.Assertions.assertThat(updated).isEqualTo(1);
    }

    /**
     * Read the rotation family of a stored token.
     */
    private UUID familyIdOf(String opaqueToken) throws Exception {
        return jdbcTemplate.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE token_hash = ?",
                UUID.class,
                sha256Hex(opaqueToken));
    }

    /**
     * Mirror of the storage hashing used by RefreshTokenService (tokens are stored as SHA-256 hex).
     */
    private static String sha256Hex(String input) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }

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
    @DisplayName("Should report the refresh token expiry that is actually stored in the database")
    void shouldReportStoredRefreshTokenExpiry() throws Exception {
        String token = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);

        String responseBody = mockMvc.perform(refreshRequest(token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(responseBody);
        java.time.Instant reported = java.time.Instant.parse(json.get("refreshTokenExpiresAt").asText());

        // Read as OffsetDateTime: java.sql.Timestamp would re-interpret timestamptz in the JVM zone
        java.time.OffsetDateTime stored = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM refresh_tokens WHERE token_hash = ?",
                java.time.OffsetDateTime.class,
                sha256Hex(json.get("refreshToken").asText()));

        org.assertj.core.api.Assertions.assertThat(reported)
                .isCloseTo(stored.toInstant(),
                        org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("Should revoke the whole token family when a rotated refresh token is reused")
    void shouldRevokeWholeFamilyOnReuse() throws Exception {
        // Given: token A rotated into token B
        String tokenA = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        String tokenB = refreshFor(tokenA);

        // When: the already-rotated token A is replayed (reuse detection)
        mockMvc.perform(refreshRequest(tokenA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));

        // Then: the still-valid token B is dead too - the whole family was revoked
        mockMvc.perform(refreshRequest(tokenB))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));
    }

    @Test
    @DisplayName("Should revoke only the reused chain, leaving the site's other tokens alive")
    void shouldRevokeOnlyTheReusedChain() throws Exception {
        // Given: two rotation chains on the SAME site - chain A stands for a past session whose
        // tokens still linger, chain B for the session in use. (Sessions do not run in parallel:
        // re-authorization supersedes the previous one - see DeviceFlowSessionSupersedeContractTest.)
        String a1 = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        String a2 = refreshFor(a1);
        String b1 = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        String b2 = refreshFor(b1);

        // When: the long-dead token A1 is replayed
        mockMvc.perform(refreshRequest(a1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));

        // Then: chain A is dead...
        mockMvc.perform(refreshRequest(a2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));

        // ...but chain B is untouched. Otherwise any stale revoked token left over from an earlier
        // session would be a repeatable denial-of-service against the site's current session.
        mockMvc.perform(refreshRequest(b2))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should carry the family id through every rotation hop")
    void shouldCarryFamilyIdThroughRotations() throws Exception {
        String first = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        UUID familyId = familyIdOf(first);
        org.assertj.core.api.Assertions.assertThat(familyId).isNotNull();

        String token = first;
        for (int i = 0; i < 3; i++) {
            token = refreshFor(token);
            org.assertj.core.api.Assertions.assertThat(familyIdOf(token))
                    .as("family id must survive rotation hop %d", i + 1)
                    .isEqualTo(familyId);
        }
    }

    @Test
    @DisplayName("Should treat a pre-existing token row as a family of its own (backfill scenario)")
    void shouldTreatBackfilledTokenAsSingletonFamily() throws Exception {
        // Given: a live session, plus a legacy revoked row written without an explicit family -
        // exactly what V43 backfills: one own family per existing row
        String live = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        String liveRotated = refreshFor(live);

        // Opaque tokens are 43-char base64url (the endpoint rejects anything else as malformed)
        String legacyToken = "legacyA".repeat(7).substring(0, 43);
        String otherLegacyToken = "legacyB".repeat(7).substring(0, 43);
        for (String legacy : java.util.List.of(legacyToken, otherLegacyToken)) {
            jdbcTemplate.update("""
                    INSERT INTO refresh_tokens (id, site_id, token_hash, expires_at, created_at, revoked_at)
                    VALUES (gen_random_uuid(), ?, ?, now() + interval '90 days', now(), now())
                    """, STORE_03_SITE_ID, sha256Hex(legacy));
        }

        // Backfill gives every such row a family of exactly one: distinct from the live chain
        // and from each other (the volatile default is evaluated per row, not once)
        org.assertj.core.api.Assertions.assertThat(familyIdOf(legacyToken)).isNotNull();
        org.assertj.core.api.Assertions.assertThat(familyIdOf(legacyToken))
                .isNotEqualTo(familyIdOf(liveRotated))
                .isNotEqualTo(familyIdOf(otherLegacyToken));

        // When: the legacy revoked token is replayed
        mockMvc.perform(refreshRequest(legacyToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_revoked"));

        // Then: it can only kill itself - the current session survives
        mockMvc.perform(refreshRequest(liveRotated))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should allow successive rotations without killing the family")
    void shouldAllowSuccessiveRotations() throws Exception {
        String token = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);

        // Three rotations in a row must all succeed
        for (int i = 0; i < 3; i++) {
            token = refreshFor(token);
        }

        // And the last issued token is still usable
        mockMvc.perform(refreshRequest(token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should reject an expired refresh token without revoking the family")
    void shouldNotRevokeFamilyForExpiredToken() throws Exception {
        // Given: an expired token and a sibling token of the same site
        String expiredToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        String siblingToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);
        expireToken(expiredToken);

        // When: the expired token is presented → 401 expired (not revoked)
        mockMvc.perform(refreshRequest(expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("refresh_token_expired"));

        // Then: expiration is not compromise - the sibling still works
        mockMvc.perform(refreshRequest(siblingToken))
                .andExpect(status().isOk());
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
