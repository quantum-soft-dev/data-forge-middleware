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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Admin API (/admin/*).
 * <p>
 * CRITICAL: These tests MUST FAIL before implementation.
 * Purpose: Validate admin CRUD operations with Keycloak authentication.
 * </p>
 *
 * @see <a href="specs/001-technical-specification-data/contracts/admin-api-summary.md">Admin API Contract</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@DisplayName("Admin API Contract Tests")
class AdminContractTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String MOCK_ADMIN_JWT_TOKEN = "mock.admin.jwt.token";
    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final String MOCK_ACCOUNT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String MOCK_SITE_ID = "b2c3d4e5-f6a7-8901-bcde-f12345678901";
    private static final String MOCK_BATCH_ID = "c3d4e5f6-a7b8-9012-cdef-123456789012";

    // ========== Account Management Tests ==========

    /**
     * Test Case 1: Create account should return 201 with account details.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: POST /admin/accounts with valid email and name
     * Then: 201 Created with account object
     * </p>
     */
    @Test
    @DisplayName("Should create account when admin authenticated")
    void shouldCreateAccountWhenAdminAuthenticated() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com",
                  "name": "Test User"
                }
                """;

        // When: POST /admin/accounts
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.name").value("Test User"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    /**
     * Test Case 2: List accounts should return paginated results.
     */
    @Test
    @DisplayName("Should list accounts with pagination when admin authenticated")
    void shouldListAccountsWithPaginationWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/accounts with pagination params
        mockMvc.perform(get("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "createdAt,desc"))

                // Then: 200 OK with paginated response
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    /**
     * Test Case 3: Get account details should return account with statistics.
     */
    @Test
    @DisplayName("Should get account details when admin authenticated")
    void shouldGetAccountDetailsWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/accounts/{id}
        mockMvc.perform(get("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK with account details
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.isActive").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.sitesCount").isNumber())
                .andExpect(jsonPath("$.totalBatches").isNumber())
                .andExpect(jsonPath("$.totalUploadedFiles").isNumber());
    }

    /**
     * Test Case 4: Update account should return updated account.
     */
    @Test
    @DisplayName("Should update account when admin authenticated")
    void shouldUpdateAccountWhenAdminAuthenticated() throws Exception {
        String requestBody = """
                {
                  "name": "Updated Name",
                  "isActive": true
                }
                """;

        // When: PUT /admin/accounts/{id}
        mockMvc.perform(put("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 200 OK with updated account
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    /**
     * Test Case 5: Delete account should return 204.
     */
    @Test
    @DisplayName("Should soft delete account when admin authenticated")
    void shouldSoftDeleteAccountWhenAdminAuthenticated() throws Exception {
        // When: DELETE /admin/accounts/{id}
        mockMvc.perform(delete("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 204 No Content
                .andExpect(status().isNoContent());
    }

    /**
     * Test Case 6: Get account statistics should return stats.
     */
    @Test
    @DisplayName("Should get account statistics when admin authenticated")
    void shouldGetAccountStatisticsWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/accounts/{id}/stats
        mockMvc.perform(get("/api/admin/accounts/{id}/stats", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK with statistics
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.sitesCount").isNumber())
                .andExpect(jsonPath("$.activeSites").isNumber())
                .andExpect(jsonPath("$.totalBatches").isNumber())
                .andExpect(jsonPath("$.completedBatches").isNumber())
                .andExpect(jsonPath("$.failedBatches").isNumber())
                .andExpect(jsonPath("$.totalFiles").isNumber())
                .andExpect(jsonPath("$.totalStorageSize").isNumber());
    }

    // ========== CreateAccountRequestDto Validation Tests ==========

    /**
     * Test Case 6a: Create account with blank email should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with blank email")
    void shouldRejectAccountCreationWithBlankEmail() throws Exception {
        String requestBody = """
                {
                  "email": "",
                  "name": "Test User"
                }
                """;

        // When: POST /admin/accounts with blank email
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6b: Create account with invalid email format should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with invalid email format")
    void shouldRejectAccountCreationWithInvalidEmailFormat() throws Exception {
        String requestBody = """
                {
                  "email": "not-an-email",
                  "name": "Test User"
                }
                """;

        // When: POST /admin/accounts with invalid email
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6c: Create account with blank name should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with blank name")
    void shouldRejectAccountCreationWithBlankName() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com",
                  "name": ""
                }
                """;

        // When: POST /admin/accounts with blank name
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6d: Create account with name too short should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with name too short")
    void shouldRejectAccountCreationWithNameTooShort() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com",
                  "name": "A"
                }
                """;

        // When: POST /admin/accounts with name too short (< 2 chars)
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6e: Create account with name too long should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with name too long")
    void shouldRejectAccountCreationWithNameTooLong() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com",
                  "name": "%s"
                }
                """.formatted("A".repeat(101)); // 101 characters (max is 100)

        // When: POST /admin/accounts with name too long (> 100 chars)
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6f: Create account with missing email field should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with missing email field")
    void shouldRejectAccountCreationWithMissingEmail() throws Exception {
        String requestBody = """
                {
                  "name": "Test User"
                }
                """;

        // When: POST /admin/accounts without email field
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 6g: Create account with missing name field should return 400.
     */
    @Test
    @DisplayName("Should reject account creation with missing name field")
    void shouldRejectAccountCreationWithMissingName() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com"
                }
                """;

        // When: POST /admin/accounts without name field
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    // ========== UpdateAccountRequestDto Validation Tests ==========

    /**
     * Test Case 7a: Update account with blank name should return 400.
     */
    @Test
    @DisplayName("Should reject account update with blank name")
    void shouldRejectAccountUpdateWithBlankName() throws Exception {
        String requestBody = """
                {
                  "name": ""
                }
                """;

        // When: PUT /admin/accounts/{id} with blank name
        mockMvc.perform(put("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 7b: Update account with name too short should return 400.
     */
    @Test
    @DisplayName("Should reject account update with name too short")
    void shouldRejectAccountUpdateWithNameTooShort() throws Exception {
        String requestBody = """
                {
                  "name": "A"
                }
                """;

        // When: PUT /admin/accounts/{id} with name too short (< 2 chars)
        mockMvc.perform(put("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 7c: Update account with name too long should return 400.
     */
    @Test
    @DisplayName("Should reject account update with name too long")
    void shouldRejectAccountUpdateWithNameTooLong() throws Exception {
        String requestBody = """
                {
                  "name": "%s"
                }
                """.formatted("A".repeat(101)); // 101 characters (max is 100)

        // When: PUT /admin/accounts/{id} with name too long (> 100 chars)
        mockMvc.perform(put("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 7d: Update account with missing name field should return 400.
     */
    @Test
    @DisplayName("Should reject account update with missing name field")
    void shouldRejectAccountUpdateWithMissingName() throws Exception {
        String requestBody = "{}";

        // When: PUT /admin/accounts/{id} without name field
        mockMvc.perform(put("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    // ========== Site Management Tests ==========

    /**
     * Test Case 7: Create site should return 201 with clientSecret.
     */
    @Test
    @DisplayName("Should create site when admin authenticated")
    void shouldCreateSiteWhenAdminAuthenticated() throws Exception {
        String requestBody = """
                {
                  "domain": "store-101.example.com",
                  "displayName": "Store #1"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 201 Created with clientSecret
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.accountId").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.domain").value("store-101.example.com"))
                .andExpect(jsonPath("$.name").value("Store #1"))
                .andExpect(jsonPath("$.clientSecret").exists())
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    /**
     * Test Case 8: List sites for account should return array.
     */
    @Test
    @DisplayName("Should list sites for account when admin authenticated")
    void shouldListSitesForAccountWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/accounts/{accountId}/sites
        mockMvc.perform(get("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK with site array
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    /**
     * Test Case 9: Delete site should return 204.
     */
    @Test
    @DisplayName("Should soft delete site when admin authenticated")
    void shouldSoftDeleteSiteWhenAdminAuthenticated() throws Exception {
        // When: DELETE /admin/sites/{id}
        mockMvc.perform(delete("/api/admin/sites/{id}", MOCK_SITE_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 204 No Content
                .andExpect(status().isNoContent());
    }

    // ========== CreateSiteRequestDto Validation Tests ==========

    /**
     * Test Case 9a: Create site with blank domain should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with blank domain")
    void shouldRejectSiteCreationWithBlankDomain() throws Exception {
        String requestBody = """
                {
                  "domain": "",
                  "displayName": "Test Site"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites with blank domain
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9b: Create site with domain too short should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with domain too short")
    void shouldRejectSiteCreationWithDomainTooShort() throws Exception {
        String requestBody = """
                {
                  "domain": "ab",
                  "displayName": "Test Site"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites with domain too short (< 3 chars)
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9c: Create site with domain too long should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with domain too long")
    void shouldRejectSiteCreationWithDomainTooLong() throws Exception {
        String requestBody = """
                {
                  "domain": "%s",
                  "displayName": "Test Site"
                }
                """.formatted("a".repeat(256)); // 256 characters (max is 255)

        // When: POST /admin/accounts/{accountId}/sites with domain too long (> 255 chars)
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9d: Create site with invalid domain format should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with invalid domain format")
    void shouldRejectSiteCreationWithInvalidDomainFormat() throws Exception {
        String requestBody = """
                {
                  "domain": "UPPERCASE.COM",
                  "displayName": "Test Site"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites with uppercase domain
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9e: Create site with blank displayName should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with blank display name")
    void shouldRejectSiteCreationWithBlankDisplayName() throws Exception {
        String requestBody = """
                {
                  "domain": "test.example.com",
                  "displayName": ""
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites with blank displayName
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9f: Create site with displayName too short should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with display name too short")
    void shouldRejectSiteCreationWithDisplayNameTooShort() throws Exception {
        String requestBody = """
                {
                  "domain": "test.example.com",
                  "displayName": "A"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites with displayName too short (< 2 chars)
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9g: Create site with displayName too long should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with display name too long")
    void shouldRejectSiteCreationWithDisplayNameTooLong() throws Exception {
        String requestBody = """
                {
                  "domain": "test.example.com",
                  "displayName": "%s"
                }
                """.formatted("A".repeat(101)); // 101 characters (max is 100)

        // When: POST /admin/accounts/{accountId}/sites with displayName too long (> 100 chars)
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9h: Create site with missing domain field should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with missing domain field")
    void shouldRejectSiteCreationWithMissingDomain() throws Exception {
        String requestBody = """
                {
                  "displayName": "Test Site"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites without domain field
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 9i: Create site with missing displayName field should return 400.
     */
    @Test
    @DisplayName("Should reject site creation with missing display name field")
    void shouldRejectSiteCreationWithMissingDisplayName() throws Exception {
        String requestBody = """
                {
                  "domain": "test.example.com"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites without displayName field
        mockMvc.perform(post("/api/admin/accounts/{accountId}/sites", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    // ========== Batch Management Tests ==========

    /**
     * Test Case 10: List batches with filtering should return paginated results.
     */
    @Test
    @DisplayName("Should list batches with filtering when admin authenticated")
    void shouldListBatchesWithFilteringWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/batches with filters
        mockMvc.perform(get("/api/admin/batches")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("siteId", MOCK_SITE_ID)
                        .param("status", "COMPLETED")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with paginated batches
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    /**
     * Test Case 11: Get batch details should return batch with files.
     */
    @Test
    @DisplayName("Should get batch details when admin authenticated")
    void shouldGetBatchDetailsWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/batches/{id}
        mockMvc.perform(get("/api/admin/batches/{id}", MOCK_BATCH_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK with batch details
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MOCK_BATCH_ID))
                .andExpect(jsonPath("$.siteId").exists())
                .andExpect(jsonPath("$.siteDomain").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.s3Path").exists())
                .andExpect(jsonPath("$.uploadedFilesCount").isNumber())
                .andExpect(jsonPath("$.totalSize").isNumber())
                .andExpect(jsonPath("$.files").isArray());
    }

    /**
     * Test Case 12: Delete batch should return 204.
     */
    @Test
    @DisplayName("Should delete batch metadata when admin authenticated")
    void shouldDeleteBatchMetadataWhenAdminAuthenticated() throws Exception {
        // When: DELETE /admin/batches/{id}
        mockMvc.perform(delete("/api/admin/batches/{id}", MOCK_BATCH_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 204 No Content
                .andExpect(status().isNoContent());
    }

    // ========== Error Log Management Tests ==========

    /**
     * Test Case 13: List errors with filtering should return paginated results.
     */
    @Test
    @DisplayName("Should list errors with filtering when admin authenticated")
    void shouldListErrorsWithFilteringWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/errors with filters
        mockMvc.perform(get("/api/admin/errors")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("siteId", MOCK_SITE_ID)
                        .param("type", "FileReadError")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with paginated errors
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    /**
     * Test Case 14: Export errors to CSV should return CSV file.
     */
    @Test
    @DisplayName("Should export errors to CSV when admin authenticated")
    void shouldExportErrorsToCsvWhenAdminAuthenticated() throws Exception {
        // When: GET /admin/errors/export
        mockMvc.perform(get("/api/admin/errors/export")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("siteId", MOCK_SITE_ID))

                // Then: 200 OK with CSV content
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().exists("Content-Disposition"));
    }

    // ========== Authorization Tests ==========

    /**
     * Test Case 15: Non-admin user should receive 403 Forbidden.
     */
    @Test
    @DisplayName("Should reject admin access when user lacks ROLE_ADMIN")
    void shouldRejectAdminAccessWhenUserLacksRoleAdmin() throws Exception {
        // When: GET /admin/accounts with non-admin token
        mockMvc.perform(get("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))

                // Then: 403 Forbidden
                .andExpect(status().isForbidden());
    }

    /**
     * Test Case 16: Missing authentication should return 401.
     */
    @Test
    @DisplayName("Should reject admin access when authentication missing")
    void shouldRejectAdminAccessWhenAuthenticationMissing() throws Exception {
        // When: GET /admin/accounts without Authorization header
        mockMvc.perform(get("/api/admin/accounts"))

                // Then: 401 Unauthorized
                .andExpect(status().isUnauthorized());
    }
}
