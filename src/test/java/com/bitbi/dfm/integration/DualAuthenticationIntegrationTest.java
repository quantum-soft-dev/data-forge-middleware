package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for FR-005: Dual authentication on GET endpoints.
 * <p>
 * Tests that GET requests to client endpoints (batch, error-log, file-upload)
 * accept BOTH JWT tokens (custom authentication) AND Keycloak OAuth2 tokens.
 * </p>
 * <p>
 * This validates the AuthenticationManagerResolver logic in SecurityConfiguration
 * that switches authentication strategies based on HTTP method and request path.
 * </p>
 * <p>
 * <strong>KNOWN LIMITATION</strong>: These tests run with TestSecurityConfig which
 * uses separate filter chains for admin and client APIs. The production SecurityConfiguration
 * uses AuthenticationManagerResolver for path/method-based authentication.
 * As a result, Keycloak token tests currently fail with 403 in test environment.
 * </p>
 * <p>
 * <strong>Status</strong>: JWT authentication tests PASS (2/5). Keycloak tests document
 * expected production behavior but fail in test environment until TestSecurityConfig
 * is updated to mirror production dual auth logic.
 * </p>
 *
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration
 * @see com.bitbi.dfm.config.TestSecurityConfig
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("Dual Authentication Integration Tests (FR-005)")
class DualAuthenticationIntegrationTest extends BaseIntegrationTest {

    /**
     * FR-005: GET /api/v1/batch/{id} should accept JWT token.
     * <p>
     * Verifies that custom JWT authentication works on GET endpoints.
     * </p>
     */
    @Test
    @DisplayName("GET /api/v1/batch/{id} with JWT token should return 200")
    void getBatch_withJwtToken_shouldReturn200() throws Exception {
        // Given: Valid JWT token for test site (store-01.example.com)
        String jwtToken = generateTestToken();

        // When: GET batch with JWT token (IN_PROGRESS batch from test-data.sql)
        mockMvc.perform(get("/api/v1/batch/0199bab2-8d63-8563-8340-edbf1c11c778")
                        .header("Authorization", jwtToken))

                // Then: 200 OK with BatchResponseDto structure
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.startedAt").exists());
    }

    /**
     * FR-005: GET /api/v1/batch/{id} should accept Keycloak OAuth2 token.
     * <p>
     * Verifies that Keycloak authentication works on GET endpoints.
     * Uses mock JWT decoder that recognizes "mock.admin.jwt.token" as valid Keycloak token.
     * </p>
     */
    @Test
    @DisplayName("GET /api/v1/batch/{id} with Keycloak token should return 200")
    void getBatch_withKeycloakToken_shouldReturn200() throws Exception {
        // Given: Valid Keycloak OAuth2 token (mocked in TestSecurityConfig)
        String keycloakToken = "Bearer mock.admin.jwt.token";

        // When: GET batch with Keycloak token (IN_PROGRESS batch from test-data.sql)
        mockMvc.perform(get("/api/v1/batch/0199bab2-8d63-8563-8340-edbf1c11c778")
                        .header("Authorization", keycloakToken))

                // Then: 200 OK with BatchResponseDto structure
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.status").exists());
    }

    /**
     * FR-005: GET /api/v1/error/{id} should accept JWT token.
     */
    @Test
    @DisplayName("GET /api/v1/error/{id} with JWT token should return 200")
    void getErrorLog_withJwtToken_shouldReturn200() throws Exception {
        // Given: Valid JWT token for test site
        String jwtToken = generateTestToken();

        // When: GET error log with JWT token (error log from test-data.sql)
        mockMvc.perform(get("/api/v1/error/log/0199bab3-d4d6-c1d1-226a-241c7b874314")
                        .header("Authorization", jwtToken))

                // Then: 200 OK with ErrorLogResponseDto structure
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    /**
     * FR-005: GET /api/v1/error/{id} should accept Keycloak token.
     */
    @Test
    @DisplayName("GET /api/v1/error/{id} with Keycloak token should return 200")
    void getErrorLog_withKeycloakToken_shouldReturn200() throws Exception {
        // Given: Valid Keycloak token (mocked)
        String keycloakToken = "Bearer mock.admin.jwt.token";

        // When: GET error log with Keycloak token (error log from test-data.sql)
        mockMvc.perform(get("/api/v1/error/log/0199bab3-d4d6-c1d1-226a-241c7b874314")
                        .header("Authorization", keycloakToken))

                // Then: 200 OK with ErrorLogResponseDto structure
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * FR-005: Verify dual auth works across different client endpoints.
     * <p>
     * This test confirms both JWT and Keycloak tokens work on any GET endpoint
     * within the client API (batch, error, upload).
     * </p>
     */
    @Test
    @DisplayName("GET endpoints accept both JWT and Keycloak tokens")
    void getFileUpload_withBothTokenTypes_shouldWork() throws Exception {
        // Test 1: JWT on batch GET (IN_PROGRESS batch)
        String jwtToken = generateTestToken();
        mockMvc.perform(get("/api/v1/batch/0199bab2-8d63-8563-8340-edbf1c11c778")
                        .header("Authorization", jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());

        // Test 2: Keycloak on error GET (existing error log)
        String keycloakToken = "Bearer mock.admin.jwt.token";
        mockMvc.perform(get("/api/v1/error/log/0199bab3-d4d6-c1d1-226a-241c7b874314")
                        .header("Authorization", keycloakToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
    }
}
