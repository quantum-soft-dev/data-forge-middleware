package com.bitbi.dfm.contract;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for the legacy Authentication API (POST /api/v1/auth/token).
 * <p>
 * Retargeted from {@code /api/v1/device/auth/token} — a path that never had a controller — to
 * {@link ApiRoutes#AUTH_TOKEN}, the only mapping that implements this Basic Auth contract
 * (deprecated {@code AuthController}).
 * </p>
 * <p>
 * Still disabled: the mapping exists but is unreachable. The Order 5 {@code /api/v1/**} OAuth2
 * filter chain matches {@code /api/v1/auth/token} before the default chain, so every request is
 * answered with 401 by Spring Security and {@code AuthController} is never invoked. Auth V2 issues
 * tokens through the Device Authorization Flow instead. Re-enable once the owner decides whether to
 * re-open the endpoint (permitAll in the Order 5 chain) or delete the controller;
 * {@code SecurityFilterChainTest#basicAuthTokenIssuanceShouldNotBeReachable} pins today's behavior.
 * </p>
 *
 * @see <a href="specs/001-technical-specification-data/contracts/auth-api.md">Authentication API Contract</a>
 */
@Disabled("Unreachable: /api/v1/auth/token is shadowed by the Order 5 /api/v1/** OAuth2 chain")
@DisplayName("Authentication API Contract Tests")
class AuthContractTest extends BaseIntegrationTest {

    private static final String AUTH_TOKEN_ENDPOINT = ApiRoutes.AUTH_TOKEN;

    /**
     * Test Case 1: Valid credentials should issue JWT token.
     * <p>
     * Given: An active site with valid domain and clientSecret
     * When: POST /api/v1/auth/token with Basic Auth (domain:clientSecret)
     * Then: 200 OK with JWT token structure
     * </p>
     */
    @Test
    @DisplayName("Should issue JWT token when valid credentials provided")
    void shouldIssueJwtTokenWhenValidCredentialsProvided() throws Exception {
        // Given: Active site with valid credentials
        String credentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

        // When: POST /api/v1/auth/token with Basic Auth
        mockMvc.perform(post(AUTH_TOKEN_ENDPOINT)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 200 OK with JWT token structure
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.siteId").exists());
    }

    /**
     * Test Case 2: Invalid secret should reject authentication.
     * <p>
     * Given: Valid domain with wrong clientSecret
     * When: POST /api/v1/auth/token with incorrect secret
     * Then: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("Should reject authentication when invalid secret provided")
    void shouldRejectAuthenticationWhenInvalidSecret() throws Exception {
        // Given: Valid domain with wrong secret
        String credentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:wrong-secret".getBytes());

        // When: POST /api/v1/auth/token
        mockMvc.perform(post(AUTH_TOKEN_ENDPOINT)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(AUTH_TOKEN_ENDPOINT));
    }

    /**
     * Test Case 3: Inactive site should reject authentication.
     * <p>
     * Given: Inactive site (isActive = false)
     * When: POST /api/v1/auth/token with valid credentials
     * Then: 401 Unauthorized with "Invalid credentials" message
     * </p>
     */
    @Test
    @DisplayName("Should reject authentication when site is inactive")
    void shouldRejectAuthenticationWhenSiteInactive() throws Exception {
        // Given: Inactive site
        String credentials = Base64.getEncoder()
                .encodeToString("inactive-site.com:some-secret".getBytes());

        // When: POST /api/v1/auth/token
        mockMvc.perform(post(AUTH_TOKEN_ENDPOINT)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"))
                .andExpect(jsonPath("$.path").value(AUTH_TOKEN_ENDPOINT));
    }

    /**
     * Test Case 4: Missing Authorization header should return 401.
     */
    @Test
    @DisplayName("Should reject authentication when Authorization header is missing")
    void shouldRejectAuthenticationWhenAuthorizationHeaderMissing() throws Exception {
        // When: POST /api/v1/auth/token without Authorization header
        mockMvc.perform(post(AUTH_TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test Case 5: Malformed Authorization header should return 401.
     */
    @Test
    @DisplayName("Should reject authentication when Authorization header is malformed")
    void shouldRejectAuthenticationWhenAuthorizationHeaderMalformed() throws Exception {
        // When: POST /api/v1/auth/token with malformed header
        mockMvc.perform(post(AUTH_TOKEN_ENDPOINT)
                        .header("Authorization", "Invalid Header")
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized());
    }
}
