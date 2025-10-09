package com.bitbi.dfm.contract;

import com.bitbi.dfm.config.TestSecurityConfig;
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

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Authentication API (POST /api/v1/auth/token).
 * <p>
 * CRITICAL: These tests MUST FAIL before implementation.
 * Purpose: Validate API contract compliance before building the actual endpoint.
 * </p>
 *
 * @see <a href="specs/001-technical-specification-data/contracts/auth-api.md">Authentication API Contract</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@DisplayName("Authentication API Contract Tests")
class AuthContractTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String AUTH_TOKEN_ENDPOINT = "/api/v1/auth/token";

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
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.domain").exists());
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
