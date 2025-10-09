package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T040: E2E Integration Test - Scenario 5: Admin Endpoints (Keycloak-Only).
 * <p>
 * Implements quickstart Scenario 5: Verify admin endpoints accept Keycloak only, reject JWT.
 * </p>
 * <p>
 * <strong>Production Behavior (FR-008, FR-009)</strong>: Admin endpoints (/api/v1/admin/**)
 * accept ONLY Keycloak OAuth2 tokens. JWT tokens are rejected with 403 Forbidden.
 * </p>
 * <p>
 * <strong>Test Environment Behavior</strong>: TestSecurityConfig uses separate filter chains:
 * <ul>
 *   <li>Client API (/api/v1/**) → JwtAuthenticationFilter → accepts JWT only</li>
 *   <li>Admin API (/admin/**) → OAuth2 Resource Server → accepts Keycloak only</li>
 * </ul>
 * Test environment behavior matches production for admin endpoints.
 * </p>
 *
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration Production dual auth configuration
 * @see com.bitbi.dfm.config.TestSecurityConfig Test security configuration
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("T040: E2E - Scenario 5: Admin Endpoints (Keycloak-Only)")
class AdminEndpointsIntegrationTest extends BaseIntegrationTest {

    /**
     * Scenario 5a: Admin endpoint with Keycloak token should return 200 OK with paged data.
     * <p>
     * Verifies Keycloak authentication works on admin endpoints (test + production behavior).
     * </p>
     */
    @Test
    @DisplayName("listAccounts_withKeycloak_shouldReturn200AndPagedDto")
    void listAccounts_withKeycloak_shouldReturn200AndPagedDto() throws Exception {
        // Given: Valid Keycloak OAuth2 token (mocked in TestSecurityConfig)
        String keycloakToken = "Bearer mock.admin.jwt.token";

        // When: GET admin accounts list with Keycloak token
        mockMvc.perform(get("/admin/accounts")
                        .header("Authorization", keycloakToken))

                // Then: 200 OK with PageResponseDto<AccountResponseDto>
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].email").exists())
                .andExpect(jsonPath("$.content[0].name").exists())
                .andExpect(jsonPath("$.content[0].isActive").isBoolean())
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[0].maxConcurrentBatches").isNumber())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    /**
     * Scenario 5b: Admin endpoint with JWT token should return 403 Forbidden.
     * <p>
     * <strong>Production Behavior (FR-008, FR-009)</strong>: Admin endpoints reject JWT tokens
     * via AuthenticationManagerResolver in SecurityConfiguration.
     * </p>
     * <p>
     * <strong>Test Environment Behavior</strong>: TestSecurityConfig's separate filter chains
     * also reject JWT tokens on admin endpoints (matches production behavior).
     * </p>
     */
    @Test
    @DisplayName("listAccounts_withJwt_shouldReturn403")
    void listAccounts_withJwt_shouldReturn403() throws Exception {
        // Given: Valid JWT token (for client API)
        String jwtToken = generateTestToken();

        // When: GET admin accounts list with JWT token
        mockMvc.perform(get("/admin/accounts")
                        .header("Authorization", jwtToken))

                // Then: 403 Forbidden (test + production behavior match)
                .andExpect(status().isForbidden());

        // Note: In production, this would return ErrorResponseDto with generic auth failure message (FR-014)
    }

    /**
     * Additional verification: Sites admin endpoint with JWT token should return 403.
     */
    @Test
    @DisplayName("listSites_withJwt_shouldReturn403")
    void listSites_withJwt_shouldReturn403() throws Exception {
        // Given: Valid JWT token (for client API)
        String jwtToken = generateTestToken();

        // When: GET admin sites list with JWT token
        mockMvc.perform(get("/admin/sites")
                        .header("Authorization", jwtToken))

                // Then: 403 Forbidden (test + production behavior match)
                .andExpect(status().isForbidden());
    }

    /**
     * Additional verification: Sites admin endpoint with Keycloak token should return 200.
     */
    @Test
    @DisplayName("listSites_withKeycloak_shouldReturn200AndPagedDto")
    void listSites_withKeycloak_shouldReturn200AndPagedDto() throws Exception {
        // Given: Valid Keycloak token
        String keycloakToken = "Bearer mock.admin.jwt.token";

        // When: GET admin sites list with Keycloak token
        mockMvc.perform(get("/admin/sites")
                        .header("Authorization", keycloakToken))

                // Then: 200 OK with PageResponseDto<SiteResponseDto>
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }
}
