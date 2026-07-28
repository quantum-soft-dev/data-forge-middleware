package com.bitbi.dfm.security;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

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
 *   <li>UI/Admin API (/api/v1/**) → Auth0 OAuth2 authentication only</li>
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
@DisplayName("Security Filter Chain Contract Tests")
class SecurityFilterChainTest extends BaseIntegrationTest {

    @Autowired
    private com.bitbi.dfm.auth.application.TokenService tokenService;

    // Test Tokens (generated dynamically to get real Custom JWT)
    // Using store-03.example.com because it has no IN_PROGRESS batches (only COMPLETED)
    private String getCustomJwtToken() {
        com.bitbi.dfm.auth.domain.JwtToken token = tokenService.generateToken("store-03.example.com", "batch-test-secret");
        return token.token();
    }

    private static final String OAUTH2_ADMIN_TOKEN = "mock.admin.jwt.token"; // Mock Auth0 token with ROLE_ADMIN
    private static final String OAUTH2_USER_TOKEN = "mock.user.jwt.token"; // Mock Auth0 token with ROLE_USER

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
     * TC02: Device API with Auth0 token → 401 Unauthorized
     * <p>
     * <b>Given</b>: A valid Auth0 OAuth2 token (intended for UI/Admin API)<br>
     * <b>When</b>: Requesting Device API endpoint (/api/v1/device/batches/start)<br>
     * <b>Then</b>: Request is rejected with 401 Unauthorized (invalid token for Custom JWT filter)
     * </p>
     * <p>
     * <b>Expected Behavior</b>: Device API filter chain should reject Auth0 tokens
     * because they don't pass through JwtAuthenticationFilter validation (returns 401, not 403).
     * </p>
     */
    @Test
    @DisplayName("TC02: Device API with Auth0 token should return 401 Unauthorized")
    void deviceApiWithAuth0TokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_BATCHES_START)
                        .header("Authorization", "Bearer " + OAUTH2_ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteId\":\"s1t2e3s4-5678-90ab-cdef-123456789012\"}"))
                .andExpect(status().isUnauthorized()) // Expecting 401 Unauthorized (Auth0 token invalid for Custom JWT filter)
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
     * TC04: Admin API with Auth0 token → 200 OK (authorized)
     * <p>
     * <b>Given</b>: A valid Auth0 OAuth2 token with ROLE_ADMIN<br>
     * <b>When</b>: Requesting Admin API endpoint (/api/v1/accounts)<br>
     * <b>Then</b>: Request is accepted (200 OK)
     * </p>
     * <p>
     * <b>Filter Chain</b>: Should route to adminApiFilterChain (@Order(2))
     * </p>
     */
    @Test
    @DisplayName("TC04: Admin API with Auth0 token should return 200 OK (authorized)")
    void adminApiWithAuth0TokenShouldBeAuthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.ACCOUNTS)
                        .header("Authorization", "Bearer " + OAUTH2_ADMIN_TOKEN)
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
     * TC07: /api/v1/device/auth/token is not part of the API surface.
     * <p>
     * <b>Given</b>: Valid Basic Auth credentials (domain:clientSecret)<br>
     * <b>When</b>: POSTing to the removed device token path (/api/v1/device/auth/token)<br>
     * <b>Then</b>: Request is rejected by the Device API chain with 401 and no token is issued
     * </p>
     * <p>
     * The path never had a controller, yet it used to be permitAll in two filter chains, which made
     * a non-existent route publicly reachable. Guards against the permitAll rules coming back.
     * </p>
     */
    @Test
    @DisplayName("TC07: Removed device token path should not issue a token")
    void removedDeviceAuthTokenPathShouldNotIssueToken() throws Exception {
        String credentials = java.util.Base64.getEncoder()
                .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

        mockMvc.perform(post("/api/v1/device/auth/token")
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized()) // Device API chain: authenticated() → 401
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * TC07a: The removed device token path is not anonymously reachable either.
     * <p>
     * <b>Given</b>: No credentials at all<br>
     * <b>When</b>: POSTing to /api/v1/device/auth/token<br>
     * <b>Then</b>: 401 Unauthorized — the path must not be permitAll
     * </p>
     */
    @Test
    @DisplayName("TC07a: Removed device token path should not be public")
    void removedDeviceAuthTokenPathShouldNotBePublic() throws Exception {
        mockMvc.perform(post("/api/v1/device/auth/token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // TC07c (Basic Auth token issuance is not reachable) moved to AuthTokenSurfaceContractTest,
    // which additionally asserts that no controller maps the path at all — a strictly stronger
    // guard now that AuthController has been deleted.

    /**
     * TC07b: Device auth refresh endpoint should be accessible without authentication.
     * <p>
     * <b>Given</b>: No Authorization header<br>
     * <b>When</b>: POST /api/v1/device/auth/refresh with invalid body<br>
     * <b>Then</b>: 400 Bad Request (not 401 — endpoint is public)
     * </p>
     */
    @Test
    @DisplayName("TC07b: Device auth refresh endpoint should be public (no auth required)")
    void deviceAuthRefreshEndpointShouldBePublic() throws Exception {
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"invalid-token\"}"))
                .andExpect(status().isBadRequest()); // 400 not 401 → endpoint is reachable
    }

    /**
     * TC08: User History API with Auth0 user token → 200 OK (authorized)
     * <p>
     * <b>Given</b>: A valid Auth0 OAuth2 token (any authenticated user, not just admin)<br>
     * <b>When</b>: Requesting User History API endpoint (/api/v1/history/batches)<br>
     * <b>Then</b>: Request is accepted (200 OK)
     * </p>
     * <p>
     * <b>Filter Chain</b>: Should route to adminApiFilterChain (@Order(2)) since /api/v1/** matches
     * </p>
     */
    @Test
    @DisplayName("TC08: User History API with Auth0 user token should return 200 OK (authorized)")
    void userHistoryApiWithAuth0UserTokenShouldBeAuthorized() throws Exception {
        mockMvc.perform(get(ApiRoutes.HISTORY_BATCHES)
                        .header("Authorization", "Bearer " + OAUTH2_USER_TOKEN)
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

    /**
     * TC10: Parquet Export listing without Basic Auth → 401 with Basic challenge (028).
     * <p>
     * <b>Filter Chain</b>: parquetExportPluginApiFilterChain — never the OAuth2 catch-all.
     * </p>
     */
    @Test
    @DisplayName("TC10: Parquet Export files without credentials should return 401 with Basic challenge")
    void parquetExportFilesWithoutCredentialsShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/plugins/parquet-export/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"parquet-export\""));
    }

    /**
     * TC11: Parquet Export listing with an Auth0 bearer token → 401 (028).
     * <p>
     * The route is carved out of the OAuth2 catch-all: an admin JWT that works on /api/v1/**
     * must NOT authenticate here.
     * </p>
     */
    @Test
    @DisplayName("TC11: Parquet Export files with Auth0 token should return 401 Unauthorized")
    void parquetExportFilesWithAuth0TokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/plugins/parquet-export/files")
                        .header("Authorization", "Bearer " + OAUTH2_ADMIN_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    /**
     * TC12: Parquet Export download route is anonymous at the chain level (028).
     * <p>
     * The one-time token is the credential, so the chain must let the request through to the
     * application — security must never answer 401/403 here. (The application-level 404/410
     * contract is covered by ParquetExportDownloadContractTest.)
     * </p>
     */
    @Test
    @DisplayName("TC12: Parquet Export download should be anonymous (never 401/403 from security)")
    void parquetExportDownloadShouldBeAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/plugins/parquet-export/download/unknown-token-value"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(status != 401 && status != 403,
                            "download route must be anonymous, got HTTP " + status);
                });
    }

    /**
     * TC13: The removed legacy admin prefix falls through to the catch-all deny.
     * <p>
     * <b>Given</b>: A valid Auth0 token with ROLE_ADMIN<br>
     * <b>When</b>: Requesting /api/admin/** (no controller has ever served this prefix)<br>
     * <b>Then</b>: 403 Forbidden from the default chain's denyAll — and never 200
     * </p>
     * <p>
     * Before the cleanup a dedicated Order 6 chain matched this prefix and let admin tokens
     * through to a 404. The prefix is not part of the API surface, so it must be denied outright.
     * </p>
     */
    @Test
    @DisplayName("TC13: Legacy /api/admin/** should be denied even with an admin token")
    void legacyAdminPrefixShouldBeDenied() throws Exception {
        mockMvc.perform(get("/api/admin/accounts")
                        .header("Authorization", "Bearer " + OAUTH2_ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * TC14: The removed legacy user prefixes fall through to the catch-all deny.
     * <p>
     * <b>Given</b>: A valid Auth0 user token<br>
     * <b>When</b>: Requesting /api/sites/**, /api/account/** or /api/user/**<br>
     * <b>Then</b>: 403 Forbidden — these prefixes have no controllers and no filter chain
     * </p>
     */
    @Test
    @DisplayName("TC14: Legacy user prefixes should be denied even with a user token")
    void legacyUserPrefixesShouldBeDenied() throws Exception {
        for (String path : new String[]{"/api/sites/list", "/api/account/profile", "/api/user/me"}) {
            mockMvc.perform(get(path)
                            .header("Authorization", "Bearer " + OAUTH2_USER_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * TC15: The live /api/v1/admin/** endpoints still require and honour ROLE_ADMIN.
     * <p>
     * <b>Given</b>: A valid Auth0 token with ROLE_ADMIN<br>
     * <b>When</b>: Requesting the plugin admin listing and the retention schedule settings<br>
     * <b>Then</b>: 200 OK — these share the "admin" word but live under /api/v1/** (Order 5) and
     * must be untouched by the legacy-prefix removal
     * </p>
     */
    @Test
    @DisplayName("TC15: /api/v1/admin/** should still work with ROLE_ADMIN")
    void liveAdminApiShouldStillWorkForAdmin() throws Exception {
        mockMvc.perform(get(ApiRoutes.PLUGINS_ADMIN)
                        .header("Authorization", "Bearer " + OAUTH2_ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(get(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
                        .header("Authorization", "Bearer " + OAUTH2_ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /**
     * TC16: The live /api/v1/admin/** endpoints still reject non-admins.
     * <p>
     * <b>Given</b>: A valid Auth0 token with ROLE_USER only<br>
     * <b>When</b>: Requesting /api/v1/admin/plugins<br>
     * <b>Then</b>: 403 Forbidden — unchanged by the cleanup
     * </p>
     */
    @Test
    @DisplayName("TC16: /api/v1/admin/plugins should still reject ROLE_USER")
    void liveAdminApiShouldStillRejectNonAdmin() throws Exception {
        mockMvc.perform(get(ApiRoutes.PLUGINS_ADMIN)
                        .header("Authorization", "Bearer " + OAUTH2_USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * TC17: The live owner-facing /api/v1/account/** surface is unaffected.
     * <p>
     * <b>Given</b>: A valid Auth0 user token<br>
     * <b>When</b>: Requesting /api/v1/account/errors/unread-count<br>
     * <b>Then</b>: 200 OK — only the legacy {@code /api/account/**} prefix (no {@code /v1}) is gone
     * </p>
     */
    @Test
    @DisplayName("TC17: /api/v1/account/** should be unaffected by the legacy prefix removal")
    void liveAccountApiShouldBeUnaffected() throws Exception {
        mockMvc.perform(get(ApiRoutes.GLOBAL_ERRORS_UNREAD_COUNT)
                        .header("Authorization", "Bearer " + OAUTH2_USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
