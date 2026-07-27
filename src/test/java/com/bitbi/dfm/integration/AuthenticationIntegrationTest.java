package com.bitbi.dfm.integration;

import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Scenario 1: Client Authentication and Token Acquisition.
 * <p>
 * Retargeted from {@code /api/v1/device/auth/token} — a path with no controller — to
 * {@link ApiRoutes#AUTH_TOKEN}, the deprecated {@code AuthController} mapping that implements
 * Basic Auth token acquisition.
 * </p>
 * <p>
 * Still disabled: that mapping is unreachable behind the Order 5 {@code /api/v1/**} OAuth2 filter
 * chain (see {@code AuthContractTest} for the full note). Auth V2 acquires tokens via the Device
 * Authorization Flow.
 * </p>
 *
 * @see <a href="specs/001-technical-specification-data/quickstart.md">Quickstart Scenario 1</a>
 */
@Disabled("Unreachable: /api/v1/auth/token is shadowed by the Order 5 /api/v1/** OAuth2 chain")
@DisplayName("Scenario 1: Client Authentication Integration Test")
class AuthenticationIntegrationTest extends BaseIntegrationTest {

    /**
     * Scenario 1: Client Authentication and Token Acquisition
     * <p>
     * Given: A registered site with valid domain and clientSecret
     * When: The client requests authentication with Basic Auth (domain:clientSecret)
     * Then: The system issues a time-limited JWT token (24 hours)
     * </p>
     */
    @Test
    @DisplayName("Should issue JWT token for valid site credentials")
    void shouldIssueJwtTokenForValidSiteCredentials() throws Exception {
        // Given: Active site exists in database (will be seeded in test setup)
        String domain = "test-store.example.com";
        String clientSecret = "test-client-secret-uuid";
        String credentials = Base64.getEncoder()
                .encodeToString((domain + ":" + clientSecret).getBytes());

        // When: POST /api/v1/auth/token with Basic Auth
        mockMvc.perform(post(ApiRoutes.AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials))

                // Then: 200 OK with valid JWT token
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.siteId").exists());

        // Verify: JWT token contains expected claims (siteId, accountId, domain)
        // Note: Full JWT validation will be done in domain layer tests
    }

    /**
     * Verify that JWT token contains correct claims.
     */
    @Test
    @DisplayName("Should include siteId, accountId, and domain in JWT claims")
    void shouldIncludeSiteIdAccountIdAndDomainInJwtClaims() throws Exception {
        // Given: Valid site credentials
        String domain = "test-store.example.com";
        String clientSecret = "test-client-secret-uuid";
        String credentials = Base64.getEncoder()
                .encodeToString((domain + ":" + clientSecret).getBytes());

        // When: Authenticate and receive token
        String response = mockMvc.perform(post(ApiRoutes.AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Then: Token should be parseable and contain required claims
        // Note: Actual JWT parsing will be implemented in service layer
    }

    /**
     * Verify that inactive site cannot authenticate.
     */
    @Test
    @DisplayName("Should reject authentication for inactive site")
    void shouldRejectAuthenticationForInactiveSite() throws Exception {
        // Given: Inactive site in database
        String domain = "inactive-store.example.com";
        String clientSecret = "inactive-secret";
        String credentials = Base64.getEncoder()
                .encodeToString((domain + ":" + clientSecret).getBytes());

        // When: Attempt authentication
        mockMvc.perform(post(ApiRoutes.AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    /**
     * Verify that site with inactive parent account cannot authenticate.
     */
    @Test
    @DisplayName("Should reject authentication when parent account is inactive")
    void shouldRejectAuthenticationWhenParentAccountInactive() throws Exception {
        // Given: Active site but inactive parent account
        String domain = "orphaned-store.example.com";
        String clientSecret = "orphaned-secret";
        String credentials = Base64.getEncoder()
                .encodeToString((domain + ":" + clientSecret).getBytes());

        // When: Attempt authentication
        mockMvc.perform(post(ApiRoutes.AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized());
    }
}
