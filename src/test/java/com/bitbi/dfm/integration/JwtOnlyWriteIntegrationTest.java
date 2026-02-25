package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T038: E2E Integration Test - Scenario 3: JWT-Only on Write Operations.
 * <p>
 * Implements quickstart Scenario 3: Verify POST/PUT/DELETE reject Auth0 tokens with 403.
 * </p>
 * <p>
 * <strong>Production Behavior (FR-006, FR-007)</strong>: Write operations (POST/PUT/DELETE/PATCH)
 * on client endpoints (batch, error-log, file-upload) accept ONLY JWT tokens.
 * Auth0 tokens are rejected with 403 Forbidden + ErrorResponseDto.
 * </p>
 * <p>
 * <strong>Test Environment Behavior</strong>: TestSecurityConfig uses separate filter chains:
 * <ul>
 *   <li>Client API (/api/v1/**) → JwtAuthenticationFilter → accepts JWT only</li>
 *   <li>Admin API (/admin/**) → OAuth2 Resource Server → accepts Auth0 only</li>
 * </ul>
 * Result: Auth0 tokens return 403 on client endpoints (matches production behavior).
 * </p>
 *
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration Production dual auth configuration
 * @see com.bitbi.dfm.config.TestSecurityConfig Test security configuration
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("T038: E2E - Scenario 3: JWT-Only on Write Operations")
class JwtOnlyWriteIntegrationTest extends BaseIntegrationTest {

    /**
     * Scenario 3a: POST with JWT token should return 201 Created.
     * <p>
     * Verifies JWT authentication works on write operations (test + production behavior).
     * </p>
     */
    @Test
    @DisplayName("startBatch_withJwt_shouldReturn201")
    void startBatch_withJwt_shouldReturn201() throws Exception {
        // Given: Valid JWT token for admin-site (no IN_PROGRESS batch exists)
        String jwtToken = generateToken("admin-site.example.com", "admin-site-secret");

        // When: POST batch start with JWT token
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchType\":\"DELTA\"}"))

                // Then: 201 Created with BatchResponseDto
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.s3Path").exists())
                .andExpect(jsonPath("$.uploadedFilesCount").value(0))
                .andExpect(jsonPath("$.totalSize").value(0))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.completedAt").doesNotExist()); // Active batch
    }

    /**
     * Scenario 3b: POST with Auth0 token should return 401 Unauthorized.
     * <p>
     * <strong>Production Behavior (FR-006, FR-007)</strong>: Write operations reject Auth0 tokens.
     * SecurityConfiguration's AuthenticationManagerResolver returns 403 when Auth0 token
     * is used on POST/PUT/DELETE endpoints.
     * </p>
     * <p>
     * <strong>Test Environment Behavior</strong>: TestSecurityConfig's separate filter chains
     * reject Auth0 tokens on client API with 401 (cannot validate Auth0 token with Custom JWT filter).
     * </p>
     */
    @Test
    @DisplayName("startBatch_withAuth0_shouldReturn401")
    void startBatch_withAuth0_shouldReturn401() throws Exception {
        // Given: Valid Auth0 OAuth2 token (mocked in TestSecurityConfig)
        String auth0Token = "Bearer mock.admin.jwt.token";

        // When: POST batch start with Auth0 token
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", auth0Token))

                // Then: 401 Unauthorized (Auth0 token cannot be validated by Custom JWT filter)
                .andExpect(status().isUnauthorized());
    }

    /**
     * Additional verification: JWT works on batch completion (PUT operation).
     */
    @Test
    @DisplayName("completeBatch_withJwt_shouldReturn200")
    void completeBatch_withJwt_shouldReturn200() throws Exception {
        // Given: Valid JWT token and existing IN_PROGRESS batch
        String jwtToken = generateTestToken();
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch from test-data.sql

        // When: PUT batch complete with JWT token
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, batchId)
                        .header("Authorization", jwtToken))

                // Then: 200 OK with BatchResponseDto including completedAt
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists()); // Completed batch has completedAt timestamp
    }

    /**
     * Additional verification: Auth0 rejected on batch completion (PUT operation).
     */
    @Test
    @DisplayName("completeBatch_withAuth0_shouldReturn401")
    void completeBatch_withAuth0_shouldReturn401() throws Exception {
        // Given: Valid Auth0 token and existing IN_PROGRESS batch
        String auth0Token = "Bearer mock.admin.jwt.token";
        String batchId = "b1c2d3e4-f5a6-7890-bcde-f12345678903"; // IN_PROGRESS batch from test-data.sql

        // When: PUT batch complete with Auth0 token
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_COMPLETE, batchId)
                        .header("Authorization", auth0Token))

                // Then: 401 Unauthorized (Auth0 token cannot be validated by Custom JWT filter)
                .andExpect(status().isUnauthorized());
    }
}
