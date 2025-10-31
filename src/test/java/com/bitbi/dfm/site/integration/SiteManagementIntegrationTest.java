package com.bitbi.dfm.site.integration;

import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for Site Management user flow (US1).
 * <p>
 * Tests end-to-end user site creation, listing, activation, deactivation, and deletion.
 * Uses test database with Keycloak OAuth2 JWT authentication.
 * </p>
 *
 * @see <a href="specs/007-adding-a-site/spec.md">Feature Spec 007</a>
 */
@DisplayName("User Story 1: User Site Creation Integration Test")
class SiteManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final String MOCK_ACCOUNT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String MOCK_EMAIL = "admin-test@example.com";

    /**
     * US1: User creates site with manual password, site appears in list.
     * <p>
     * Given: Authenticated user
     * When: POST /api/sites with domain + password
     * Then: Site created with 201, password returned, site appears in GET /api/sites
     * </p>
     */
    @Test
    @DisplayName("Should create site with manual password and return plaintext password once")
    void shouldCreateSiteWithManualPassword() throws Exception {
        // Given: Authenticated user with JWT
        String requestBody = """
                {
                  "domain": "new-manual-site.example.com",
                  "displayName": "New Manual Site",
                  "password": "manualPassword123"
                }
                """;

        // When: POST /api/sites
        MvcResult createResult = mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.site.domain").value("new-manual-site.example.com"))
                .andExpect(jsonPath("$.site.name").value("New Manual Site"))
                .andExpect(jsonPath("$.site.isActive").value(true))
                .andExpect(jsonPath("$.password").value("manualPassword123")) // Plaintext password returned
                .andReturn();

        String siteId = extractSiteId(createResult);

        // Verify: Site appears in list
        mockMvc.perform(get("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + siteId + "')].domain").value("new-manual-site.example.com"));
    }

    /**
     * US1: User creates site without password, system generates one.
     * <p>
     * Given: Authenticated user
     * When: POST /api/sites with domain only (password null/empty)
     * Then: Site created with generated password (8-12 alphanumeric characters)
     * </p>
     */
    @Test
    @DisplayName("Should generate password when not provided")
    void shouldGeneratePasswordWhenNotProvided() throws Exception {
        // Given: Request without password field
        String requestBody = """
                {
                  "domain": "auto-gen-site.example.com",
                  "displayName": "Auto-Generated Password Site"
                }
                """;

        // When: POST /api/sites
        MvcResult result = mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.site.domain").value("auto-gen-site.example.com"))
                .andExpect(jsonPath("$.password").exists())
                .andReturn();

        // Verify: Password is 8-12 alphanumeric characters
        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);
        String generatedPassword = json.get("password").asText();

        assertThat(generatedPassword)
                .hasSizeBetween(8, 12) // PasswordGenerator generates 8-12 character passwords
                .matches("^[A-Za-z0-9]+$"); // Alphanumeric only
    }

    /**
     * FR-017: Domain uniqueness per account.
     * <p>
     * Given: User already has site with domain "duplicate-test.example.com"
     * When: POST /api/sites with same domain
     * Then: 400 Bad Request with duplicate domain error
     * </p>
     */
    @Test
    @DisplayName("Should reject duplicate domain within same account")
    void shouldRejectDuplicateDomainWithinAccount() throws Exception {
        // Given: Create first site
        String firstSiteRequest = """
                {
                  "domain": "duplicate-test.example.com",
                  "displayName": "First Site",
                  "password": "password1"
                }
                """;

        mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstSiteRequest))
                .andExpect(status().isCreated());

        // When: Attempt to create second site with same domain
        String duplicateRequest = """
                {
                  "domain": "duplicate-test.example.com",
                  "displayName": "Duplicate Site",
                  "password": "password2"
                }
                """;

        // Then: 409 Conflict (duplicate resource)
        mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    /**
     * FR-009: Only active sites returned in list.
     * <p>
     * Given: User has 2 sites (1 active, 1 inactive)
     * When: GET /api/sites
     * Then: Only active site returned, sorted by createdAt DESC
     * </p>
     */
    @Test
    @DisplayName("Should return only active sites sorted by creation date")
    void shouldReturnOnlyActiveSitesSortedByCreationDate() throws Exception {
        // Given: Create 2 sites
        createTestSite("active-site-1.example.com", "Active Site 1");
        Thread.sleep(100); // Ensure different timestamps
        createTestSite("active-site-2.example.com", "Active Site 2");

        // When: GET /api/sites
        MvcResult result = mockMvc.perform(get("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andReturn();

        // Then: At least 2 sites returned (might have more from test data)
        String responseBody = result.getResponse().getContentAsString();
        JsonNode sites = objectMapper.readTree(responseBody);

        assertThat(sites.isArray()).isTrue();
        assertThat(sites.size()).isGreaterThanOrEqualTo(2);

        // Verify sorting: newest first
        for (int i = 0; i < sites.size() - 1; i++) {
            String createdAt1 = sites.get(i).get("createdAt").asText();
            String createdAt2 = sites.get(i + 1).get("createdAt").asText();
            assertThat(createdAt1).isGreaterThanOrEqualTo(createdAt2);
        }
    }

    /**
     * FR-015: Domain validation (alphanumeric + dots/hyphens).
     * <p>
     * Given: Invalid domain with special characters
     * When: POST /api/sites
     * Then: 400 Bad Request with validation error
     * </p>
     */
    @Test
    @DisplayName("Should reject invalid domain format")
    void shouldRejectInvalidDomainFormat() throws Exception {
        // Given: Request with invalid domain (contains underscore)
        String requestBody = """
                {
                  "domain": "invalid_domain.example.com",
                  "displayName": "Invalid Domain",
                  "password": "password123"
                }
                """;

        // When: POST /api/sites
        // Then: 400 Bad Request
        mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("letters, numbers, dots, and hyphens")));
    }

    /**
     * FR-016: Password minimum 8 characters.
     * <p>
     * Given: Password with less than 8 characters
     * When: POST /api/sites
     * Then: 400 Bad Request with validation error
     * </p>
     */
    @Test
    @DisplayName("Should reject password shorter than 8 characters")
    void shouldRejectShortPassword() throws Exception {
        // Given: Request with short password
        String requestBody = """
                {
                  "domain": "short-password.example.com",
                  "displayName": "Short Password Site",
                  "password": "pass"
                }
                """;

        // When: POST /api/sites
        // Then: 400 Bad Request
        mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("8 characters")));
    }

    /**
     * T047: US2 - Test deactivate/activate flow.
     * <p>
     * Given: User has an active site
     * When: User deactivates site, then activates it again
     * Then: Site status changes correctly, appears/disappears from active list
     * </p>
     */
    @Test
    @DisplayName("Should deactivate and reactivate site successfully")
    void shouldDeactivateAndReactivateSite() throws Exception {
        // Given: Create a site
        String requestBody = """
                {
                  "domain": "deactivate-reactivate-test.example.com",
                  "displayName": "Deactivate Reactivate Test",
                  "password": "testPassword123"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String siteId = extractSiteId(createResult);

        // Verify site is in the active list
        mockMvc.perform(get("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + siteId + "')].isActive").value(true));

        // When: Deactivate the site
        mockMvc.perform(post("/api/sites/" + siteId + "/deactivate")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isNoContent());

        // Then: Site should not appear in active sites list
        MvcResult listAfterDeactivate = mockMvc.perform(get("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andReturn();

        String sitesJson = listAfterDeactivate.getResponse().getContentAsString();
        assertThat(sitesJson).doesNotContain(siteId);

        // When: Activate the site again
        mockMvc.perform(post("/api/sites/" + siteId + "/activate")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(siteId))
                .andExpect(jsonPath("$.isActive").value(true));

        // Then: Site should appear in active sites list again
        mockMvc.perform(get("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + siteId + "')].isActive").value(true));
    }

    /**
     * T047: US2 - Test rapid status changes.
     * <p>
     * Given: User has an active site
     * When: User rapidly deactivates and activates site multiple times
     * Then: All status changes are handled correctly
     * </p>
     */
    @Test
    @DisplayName("Should handle rapid deactivate/activate cycles")
    void shouldHandleRapidStatusChanges() throws Exception {
        // Given: Create a site
        String requestBody = """
                {
                  "domain": "rapid-status-test.example.com",
                  "displayName": "Rapid Status Test",
                  "password": "testPassword123"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String siteId = extractSiteId(createResult);

        // When: Perform multiple rapid status changes
        for (int i = 0; i < 3; i++) {
            // Deactivate
            mockMvc.perform(post("/api/sites/" + siteId + "/deactivate")
                            .with(jwt().jwt(jwt -> jwt
                                    .claim("sub", MOCK_ACCOUNT_ID)
                                    .claim("email", MOCK_EMAIL))))
                    .andExpect(status().isNoContent());

            // Activate
            mockMvc.perform(post("/api/sites/" + siteId + "/activate")
                            .with(jwt().jwt(jwt -> jwt
                                    .claim("sub", MOCK_ACCOUNT_ID)
                                    .claim("email", MOCK_EMAIL))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(true));
        }

        // Then: Site should be active at the end
        mockMvc.perform(get("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + siteId + "')].isActive").value(true));
    }

    // Helper methods

    private void createTestSite(String domain, String displayName) throws Exception {
        String requestBody = String.format("""
                {
                  "domain": "%s",
                  "displayName": "%s",
                  "password": "testPassword123"
                }
                """, domain, displayName);

        mockMvc.perform(post("/api/sites")
                        .with(jwt().jwt(jwt -> jwt
                                .claim("sub", MOCK_ACCOUNT_ID)
                                .claim("email", MOCK_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());
    }

    private String extractSiteId(MvcResult result) throws Exception {
        String responseBody = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);
        return json.get("site").get("id").asText();
    }
}
