package com.bitbi.dfm.device.presentation;

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
 * Contract tests for Device API Authentication endpoints.
 * <p>
 * <b>Purpose</b>: Validate the Device API authentication contract (POST /api/v1/device/auth/token).
 * </p>
 * <p>
 * <b>TDD Phase</b>: RED - These tests MUST FAIL initially since DeviceAuthController
 * is not yet implemented.
 * </p>
 * <p>
 * <b>Acceptance Criteria</b>:
 * </p>
 * <ul>
 *   <li>Valid Basic Auth credentials return 200 OK with JWT token</li>
 *   <li>Invalid credentials return 401 Unauthorized</li>
 *   <li>Missing Authorization header returns 401 Unauthorized</li>
 *   <li>Malformed Authorization header returns 400 Bad Request</li>
 * </ul>
 *
 * @see com.bitbi.dfm.device.presentation.DeviceAuthController
 * @see <a href="specs/010-api-unification-goal/tasks.md">Task T007</a>
 */
@Disabled("Auth V2: Basic Auth endpoint removed. See auth-v2-migration-guide.md")
@DisplayName("Device API - Authentication Contract Tests")
class DeviceAuthContractTest extends BaseIntegrationTest {

    /**
     * TC07: Valid credentials should issue JWT token.
     * <p>
     * <b>Given</b>: An active site with valid domain and clientSecret<br>
     * <b>When</b>: POST /api/v1/device/auth/token with Basic Auth (domain:clientSecret)<br>
     * <b>Then</b>: 200 OK with JWT token structure
     * </p>
     */
    @Test
    @DisplayName("TC07: Should issue JWT token when valid credentials provided")
    void shouldIssueJwtTokenWhenValidCredentialsProvided() throws Exception {
        // Given: Active site with valid credentials from test-data.sql
        String credentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

        // When: POST /api/v1/device/auth/token with Basic Auth
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 200 OK with JWT token structure
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.siteId").value("0199baac-f852-753f-6fc3-7c994fc38654"));
    }

    /**
     * TC08: Invalid secret should reject authentication.
     * <p>
     * <b>Given</b>: Valid domain with wrong clientSecret<br>
     * <b>When</b>: POST /api/v1/device/auth/token with incorrect secret<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC08: Should reject authentication when invalid secret provided")
    void shouldRejectAuthenticationWhenInvalidSecret() throws Exception {
        // Given: Valid domain with wrong secret
        String credentials = Base64.getEncoder()
                .encodeToString("store-01.example.com:wrong-secret".getBytes());

        // When: POST /api/v1/device/auth/token
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(ApiRoutes.DEVICE_AUTH_TOKEN));
    }

    /**
     * TC09: Missing Authorization header should reject authentication.
     * <p>
     * <b>Given</b>: No Authorization header provided<br>
     * <b>When</b>: POST /api/v1/device/auth/token without credentials<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC09: Should reject authentication when Authorization header missing")
    void shouldRejectAuthenticationWhenAuthorizationHeaderMissing() throws Exception {
        // Given: No Authorization header
        // When: POST /api/v1/device/auth/token
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * TC10: Malformed Authorization header should return 400 Bad Request.
     * <p>
     * <b>Given</b>: Invalid Base64 encoding in Authorization header<br>
     * <b>When</b>: POST /api/v1/device/auth/token with malformed header<br>
     * <b>Then</b>: 400 Bad Request
     * </p>
     */
    @Test
    @DisplayName("TC10: Should return 400 Bad Request when Authorization header malformed")
    void shouldReturnBadRequestWhenAuthorizationHeaderMalformed() throws Exception {
        // Given: Malformed Authorization header (invalid Base64)
        // When: POST /api/v1/device/auth/token
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic not-valid-base64!!!")
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    /**
     * TC11: Inactive site should reject authentication.
     * <p>
     * <b>Given</b>: A site that has been deactivated (isActive = false)<br>
     * <b>When</b>: POST /api/v1/device/auth/token with valid credentials for inactive site<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC11: Should reject authentication for inactive site")
    void shouldRejectAuthenticationForInactiveSite() throws Exception {
        // Given: Inactive site with valid credentials (from test-data.sql: store-99-inactive.example.com)
        String credentials = Base64.getEncoder()
                .encodeToString("store-99-inactive.example.com:inactive-site-secret".getBytes());

        // When: POST /api/v1/device/auth/token
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    /**
     * TC12: Non-existent domain should reject authentication.
     * <p>
     * <b>Given</b>: A domain that doesn't exist in the database<br>
     * <b>When</b>: POST /api/v1/device/auth/token with non-existent domain<br>
     * <b>Then</b>: 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC12: Should reject authentication for non-existent domain")
    void shouldRejectAuthenticationForNonExistentDomain() throws Exception {
        // Given: Non-existent domain
        String credentials = Base64.getEncoder()
                .encodeToString("non-existent-domain.example.com:any-secret".getBytes());

        // When: POST /api/v1/device/auth/token
        mockMvc.perform(post(ApiRoutes.DEVICE_AUTH_TOKEN)
                        .header("Authorization", "Basic " + credentials)
                        .contentType(MediaType.APPLICATION_JSON))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }
}
