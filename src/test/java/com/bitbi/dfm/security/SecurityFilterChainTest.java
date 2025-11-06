package com.bitbi.dfm.security;

import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Security Filter Chain routing behavior.
 * <p>
 * <b>Purpose</b>: Verify that the dual security filter chains correctly route requests
 * to the appropriate authentication mechanism based on path patterns.
 * </p>
 * <p>
 * <b>Architecture</b>:
 * </p>
 * <ul>
 *   <li>Device API (/api/v1/device/**) → Custom JWT authentication only</li>
 *   <li>UI/Admin API (/api/v1/**) → Keycloak OAuth2 authentication only</li>
 * </ul>
 * <p>
 * <b>Critical Behavior</b>: Mismatched token types must be rejected with 403 Forbidden.
 * </p>
 * <p>
 * <b>TDD Phase</b>: RED - These tests MUST FAIL initially since the new security
 * filter chains are not yet implemented.
 * </p>
 *
 * @see com.bitbi.dfm.shared.config.SecurityConfiguration
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class})
@Sql("/test-data.sql")
@DisplayName("Security Filter Chain Contract Tests")
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.bitbi.dfm.auth.application.TokenService tokenService;

    // Test Tokens (generated dynamically to get real Custom JWT)
    // Using store-03.example.com because it has no IN_PROGRESS batches (only COMPLETED)
    private String getCustomJwtToken() {
        com.bitbi.dfm.auth.domain.JwtToken token = tokenService.generateToken("store-03.example.com", "batch-test-secret");
        return token.token();
    }

    private static final String KEYCLOAK_ADMIN_TOKEN = "mock.admin.jwt.token"; // Mock Keycloak token with ROLE_ADMIN
    private static final String KEYCLOAK_USER_TOKEN = "mock.user.jwt.token"; // Mock Keycloak token with ROLE_USER

    /**
     * TC01: Device API with Custom JWT → 200 OK (authorized)
     * <p>
     * <b>Given</b>: A valid Custom JWT token<br>
     * <b>When</b>: Requesting Device API endpoint (/api/v1/device/batches/start)<br>
     * <b>Then</b>: Request is accepted (200 OK or appropriate success status)
     * </p>
     * <p>
     * <b>Filter Chain</b>: Should route to deviceApiFilterChain (@Order(1))
     * </p>
     */
    @Test
    @DisplayName("TC01: Device API with Custom JWT should return 200 OK (authorized)")
    void deviceApiWithCustomJwtShouldBeAuthorized() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", "Bearer " + getCustomJwtToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"s1t2e3s4-5678-90ab-cdef-123456789012\"}"))
                .andExpect(status().isCreated()) // Expecting 201 Created for batch start
                .andExpect(jsonPath("$.id").exists());
    }

    /**
     * TC02: Device API with Keycloak token → 401 Unauthorized
     * <p>
     * <b>Given</b>: A valid Keycloak OAuth2 token (intended for UI/Admin API)<br>
     * <b>When</b>: Requesting Device API endpoint (/api/v1/device/batches/start)<br>
     * <b>Then</b>: Request is rejected with 401 Unauthorized (invalid token for Custom JWT filter)
     * </p>
     * <p>
     * <b>Expected Behavior</b>: Device API filter chain should reject Keycloak tokens
     * because they don't pass through JwtAuthenticationFilter validation (returns 401, not 403).
     * </p>
     */
    @Test
    @DisplayName("TC02: Device API with Keycloak token should return 401 Unauthorized")
    void deviceApiWithKeycloakTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", "Bearer " + KEYCLOAK_ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"s1t2e3s4-5678-90ab-cdef-123456789012\"}"))
                .andExpect(status().isUnauthorized()) // Expecting 401 Unauthorized (Keycloak token invalid for Custom JWT filter)
                .andExpect(jsonPath("$.status").doesNotExist()); // 401 doesn't return JSON body
    }

    /**
     * TC03: Device API with no token → 401 Unauthorized
     * <p>
     * <b>Given</b>: No authentication token provided<br>
     * <b>When</b>: Requesting Device API endpoint (/api/v1/device/batches/start)<br>
     * <b>Then</b>: Request is rejected with 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC03: Device API with no token should return 401 Unauthorized")
    void deviceApiWithNoTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"s1t2e3s4-5678-90ab-cdef-123456789012\"}"))
                .andExpect(status().isUnauthorized()) // Expecting 401 Unauthorized
                .andExpect(jsonPath("$.status").doesNotExist()); // 401 doesn't return JSON body
    }

    /**
     * TC04: Admin API with Keycloak token → 200 OK (authorized)
     * <p>
     * <b>Given</b>: A valid Keycloak OAuth2 token with ROLE_ADMIN<br>
     * <b>When</b>: Requesting Admin API endpoint (/api/v1/accounts)<br>
     * <b>Then</b>: Request is accepted (200 OK)
     * </p>
     * <p>
     * <b>Filter Chain</b>: Should route to adminApiFilterChain (@Order(2))
     * </p>
     */
    @Test
    @DisplayName("TC04: Admin API with Keycloak token should return 200 OK (authorized)")
    void adminApiWithKeycloakTokenShouldBeAuthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.ACCOUNTS)
                        .header("Authorization", "Bearer " + KEYCLOAK_ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()) // Expecting 200 OK
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray());
    }

    /**
     * TC05: Admin API with Custom JWT → 401 Unauthorized
     * <p>
     * <b>Given</b>: A valid Custom JWT token (intended for Device API)<br>
     * <b>When</b>: Requesting Admin API endpoint (/api/v1/accounts)<br>
     * <b>Then</b>: Request is rejected with 401 Unauthorized (invalid token for OAuth2 Resource Server)
     * </p>
     * <p>
     * <b>Expected Behavior</b>: Admin API filter chain should reject Custom JWT tokens
     * because they cannot be decoded by OAuth2 Resource Server (returns 401, not 403).
     * </p>
     */
    @Test
    @DisplayName("TC05: Admin API with Custom JWT should return 401 Unauthorized")
    void adminApiWithCustomJwtShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.ACCOUNTS)
                        .header("Authorization", "Bearer " + getCustomJwtToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()) // Expecting 401 Unauthorized (cannot decode Custom JWT)
                .andExpect(jsonPath("$.status").doesNotExist()); // 401 doesn't return JSON body
    }

    /**
     * TC06: Admin API with no token → 401 Unauthorized
     * <p>
     * <b>Given</b>: No authentication token provided<br>
     * <b>When</b>: Requesting Admin API endpoint (/api/v1/accounts)<br>
     * <b>Then</b>: Request is rejected with 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC06: Admin API with no token should return 401 Unauthorized")
    void adminApiWithNoTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.ACCOUNTS)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()) // Expecting 401 Unauthorized
                .andExpect(jsonPath("$.status").doesNotExist()); // 401 doesn't return JSON body
    }

    /**
     * TC07: Device Auth Token endpoint with Basic Auth → 200 OK (public endpoint)
     * <p>
     * <b>Given</b>: Valid Basic Auth credentials (domain:clientSecret)<br>
     * <b>When</b>: Requesting Device auth token endpoint (/api/v1/device/auth/token)<br>
     * <b>Then</b>: Request is accepted and JWT token is issued
     * </p>
     * <p>
     * <b>Note</b>: This endpoint should allow Basic Auth (not Bearer) for initial token generation.
     * </p>
     */
    @Test
    @DisplayName("TC07: Device auth token endpoint with Basic Auth should return 200 OK")
    void deviceAuthTokenEndpointWithBasicAuthShouldBeAuthorized() throws Exception {
        String credentials = java.util.Base64.getEncoder()
                .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()) // Expecting 200 OK
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.siteId").exists());
    }

    /**
     * TC08: User History API with Keycloak user token → 200 OK (authorized)
     * <p>
     * <b>Given</b>: A valid Keycloak OAuth2 token (any authenticated user, not just admin)<br>
     * <b>When</b>: Requesting User History API endpoint (/api/v1/history/batches)<br>
     * <b>Then</b>: Request is accepted (200 OK)
     * </p>
     * <p>
     * <b>Filter Chain</b>: Should route to adminApiFilterChain (@Order(2)) since /api/v1/** matches
     * </p>
     */
    @Test
    @DisplayName("TC08: User History API with Keycloak user token should return 200 OK (authorized)")
    void userHistoryApiWithKeycloakUserTokenShouldBeAuthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.HISTORY_BATCHES)
                        .header("Authorization", "Bearer " + KEYCLOAK_USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()) // Expecting 200 OK
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    /**
     * TC09: User History API with Custom JWT → 401 Unauthorized
     * <p>
     * <b>Given</b>: A valid Custom JWT token (intended for Device API)<br>
     * <b>When</b>: Requesting User History API endpoint (/api/v1/history/batches)<br>
     * <b>Then</b>: Request is rejected with 401 Unauthorized (invalid token for OAuth2 Resource Server)
     * </p>
     */
    @Test
    @DisplayName("TC09: User History API with Custom JWT should return 401 Unauthorized")
    void userHistoryApiWithCustomJwtShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.HISTORY_BATCHES)
                        .header("Authorization", "Bearer " + getCustomJwtToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()) // Expecting 401 Unauthorized (cannot decode Custom JWT)
                .andExpect(jsonPath("$.status").doesNotExist()); // 401 doesn't return JSON body
    }
}
