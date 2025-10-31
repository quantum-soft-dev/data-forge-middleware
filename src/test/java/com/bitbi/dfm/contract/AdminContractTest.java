package com.bitbi.dfm.contract;

import com.bitbi.dfm.account.infrastructure.KeycloakAdminClient;
import com.bitbi.dfm.config.TestSecurityConfig;
import org.keycloak.representations.idm.UserRepresentation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
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

    @MockBean
    private KeycloakAdminClient keycloakAdminClient;

    private static final String MOCK_ADMIN_JWT_TOKEN = "mock.admin.jwt.token";
    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final String MOCK_ACCOUNT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String MOCK_SITE_ID = "b2c3d4e5-f6a7-8901-bcde-f12345678901";
    private static final String MOCK_BATCH_ID = "c3d4e5f6-a7b8-9012-cdef-123456789012";
    private static final String MOCK_KEYCLOAK_USER_ID = "d4e5f6a7-b8c9-0123-def4-567890123456"; // Valid UUID format

    @BeforeEach
    void setUp() throws Exception {
        // Stateful mock: Track enabled state per Keycloak user ID
        Map<String, Boolean> keycloakUserEnabledState = new HashMap<>();
        keycloakUserEnabledState.put(MOCK_KEYCLOAK_USER_ID, true); // Default: enabled

        // For create user in Keycloak - return unique UUID for each call
        when(keycloakAdminClient.createUser(anyString(), anyString(), anyString(), any(Boolean.class)))
                .thenAnswer((Answer<String>) invocation -> {
                    String newUserId = UUID.randomUUID().toString();
                    keycloakUserEnabledState.put(newUserId, true); // New users start enabled
                    return newUserId;
                });

        // For update user attributes (bidirectional reference)
        doNothing().when(keycloakAdminClient).updateUserAttributes(anyString(), anyString());

        // For assign role
        doNothing().when(keycloakAdminClient).assignRole(anyString(), anyString());

        // For get user - return state based on tracked map
        when(keycloakAdminClient.getUser(anyString())).thenAnswer((Answer<UserRepresentation>) invocation -> {
            String userId = invocation.getArgument(0);
            UserRepresentation user = new UserRepresentation();
            user.setId(userId);
            user.setEnabled(keycloakUserEnabledState.getOrDefault(userId, true));
            return user;
        });

        // For get last login
        when(keycloakAdminClient.getLastLogin(anyString())).thenReturn(null);

        // For lock - update state to disabled
        doAnswer((Answer<Void>) invocation -> {
            String userId = invocation.getArgument(0);
            keycloakUserEnabledState.put(userId, false);
            return null;
        }).when(keycloakAdminClient).disableUser(anyString());

        // For unlock - update state to enabled
        doAnswer((Answer<Void>) invocation -> {
            String userId = invocation.getArgument(0);
            keycloakUserEnabledState.put(userId, true);
            return null;
        }).when(keycloakAdminClient).enableUser(anyString());

        // For reset password
        doNothing().when(keycloakAdminClient).resetPassword(anyString(), anyString());
    }

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
                  "name": "Test User",
                  "role": "USER"
                }
                """;

        // When: POST /admin/accounts/with-keycloak
        mockMvc.perform(post("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.account.id").exists())
                .andExpect(jsonPath("$.account.keycloakUserId").exists())
                .andExpect(jsonPath("$.account.keycloakUserId").isString())
                .andExpect(jsonPath("$.account.email").value("test@example.com"))
                .andExpect(jsonPath("$.account.name").value("Test User"))
                .andExpect(jsonPath("$.account.keycloakEnabled").value(true))
                .andExpect(jsonPath("$.temporaryPassword").exists())
                .andExpect(jsonPath("$.temporaryPassword").isString());
    }

    /**
     * Test Case 1a: Create account with role="USER" should return 201 with USER role assigned.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: POST /admin/accounts with role="USER"
     * Then: 201 Created with account.role="USER" and temporaryPassword
     * </p>
     */
    @Test
    @DisplayName("Should create account with USER role when specified")
    void shouldCreateAccountWithUserRole() throws Exception {
        String requestBody = """
                {
                  "email": "user@example.com",
                  "name": "Regular User",
                  "role": "USER"
                }
                """;

        // When: POST /admin/accounts/with-keycloak with role="USER"
        mockMvc.perform(post("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.account").exists())
                .andExpect(jsonPath("$.account.keycloakUserId").exists())
                .andExpect(jsonPath("$.account.keycloakUserId").isString())
                .andExpect(jsonPath("$.account.email").value("user@example.com"))
                .andExpect(jsonPath("$.account.name").value("Regular User"))
                .andExpect(jsonPath("$.account.keycloakEnabled").value(true))
                .andExpect(jsonPath("$.temporaryPassword").exists())
                .andExpect(jsonPath("$.temporaryPassword").isString());
    }

    /**
     * Test Case 1b: Create account with role="ADMIN" should return 201 with ADMIN role assigned.
     */
    @Test
    @DisplayName("Should create account with ADMIN role when specified")
    void shouldCreateAccountWithAdminRole() throws Exception {
        String requestBody = """
                {
                  "email": "admin@example.com",
                  "name": "Admin User",
                  "role": "ADMIN"
                }
                """;

        // When: POST /admin/accounts/with-keycloak with role="ADMIN"
        mockMvc.perform(post("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 201 Created
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.account").exists())
                .andExpect(jsonPath("$.account.keycloakUserId").exists())
                .andExpect(jsonPath("$.account.keycloakUserId").isString())
                .andExpect(jsonPath("$.account.email").value("admin@example.com"))
                .andExpect(jsonPath("$.account.name").value("Admin User"))
                .andExpect(jsonPath("$.account.keycloakEnabled").value(true))
                .andExpect(jsonPath("$.temporaryPassword").exists());
    }

    /**
     * Test Case 1c: Create account with missing role should return 400 Bad Request.
     */
    @Test
    @DisplayName("Should reject account creation with missing role field")
    void shouldRejectAccountCreationWithMissingRole() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com",
                  "name": "Test User"
                }
                """;

        // When: POST /admin/accounts without role field
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
     * Test Case 1d: Create account with invalid role should return 400 Bad Request.
     */
    @Test
    @DisplayName("Should reject account creation with invalid role value")
    void shouldRejectAccountCreationWithInvalidRole() throws Exception {
        String requestBody = """
                {
                  "email": "test@example.com",
                  "name": "Test User",
                  "role": "SUPERUSER"
                }
                """;

        // When: POST /admin/accounts with invalid role
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
     * Test Case 1e: Create account with duplicate email should return 409 Conflict.
     */
    @Test
    @DisplayName("Should reject account creation with duplicate email")
    void shouldRejectAccountCreationWithDuplicateEmail() throws Exception {
        String requestBody = """
                {
                  "email": "existing@example.com",
                  "name": "Duplicate User",
                  "role": "USER"
                }
                """;

        // First creation succeeds
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        // When: POST /admin/accounts with same email
        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 409 Conflict
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 1f: Create account with invalid email should return 400 Bad Request.
     */
    @Test
    @DisplayName("Should reject account creation with invalid email format")
    void shouldRejectAccountCreationWithInvalidEmailInCreateRequest() throws Exception {
        String requestBody = """
                {
                  "email": "not-a-valid-email",
                  "name": "Test User",
                  "role": "USER"
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
                .andExpect(jsonPath("$.status").exists())
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
     * Test Case 7d: Update account with no fields (empty JSON) should return 200 (no-op update).
     * All fields are optional for partial updates.
     */
    @Test
    @DisplayName("Should accept account update with no fields (partial update support)")
    void shouldAcceptAccountUpdateWithNoFields() throws Exception {
        String requestBody = "{}";

        // When: PUT /admin/accounts/{id} with empty JSON (no fields to update)
        mockMvc.perform(put("/api/admin/accounts/{id}", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))

                // Then: 200 OK (no-op update is valid)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists());
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
                  "domain": "invalid domain!@#",
                  "displayName": "Test Site"
                }
                """;

        // When: POST /admin/accounts/{accountId}/sites with invalid characters
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

    // ========== Account Actions Tests (Lock/Unlock) - User Story 2 ==========

    /**
     * Test Case 10a: Lock account should return 200 with updated status.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: POST /admin/accounts/{id}/lock
     * Then: 200 OK with AccountWithKeycloakResponse showing keycloakEnabled=false
     * </p>
     */
    @Test
    @DisplayName("Should lock account when admin authenticated")
    void shouldLockAccountWhenAdminAuthenticated() throws Exception {
        // When: POST /admin/accounts/{id}/lock
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK with updated account status
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.keycloakEnabled").value(false))
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists());
    }

    /**
     * Test Case 10b: Unlock account should return 200 with updated status.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN AND account is locked
     * When: POST /admin/accounts/{id}/unlock
     * Then: 200 OK with AccountWithKeycloakResponse showing keycloakEnabled=true
     * </p>
     */
    @Test
    @DisplayName("Should unlock account when admin authenticated")
    void shouldUnlockAccountWhenAdminAuthenticated() throws Exception {
        // Given: Lock the account first
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))
                .andExpect(status().isOk());

        // When: POST /admin/accounts/{id}/unlock
        mockMvc.perform(post("/api/admin/accounts/{id}/unlock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK with updated account status
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MOCK_ACCOUNT_ID))
                .andExpect(jsonPath("$.keycloakEnabled").value(true))
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists());
    }

    /**
     * Test Case 10c: Lock account without Keycloak integration should return 400.
     */
    @Test
    @DisplayName("Should reject lock request for account without Keycloak integration")
    void shouldRejectLockRequestForAccountWithoutKeycloakIntegration() throws Exception {
        // Account from test-data.sql without keycloak_user_id
        String accountWithoutKeycloak = "0199bab1-fad2-bf76-c478-eae1f61e1c17";

        // When: POST /admin/accounts/{id}/lock for account without Keycloak
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", accountWithoutKeycloak)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 10d: Lock already locked account should return 400.
     */
    @Test
    @DisplayName("Should reject lock request for already locked account")
    void shouldRejectLockRequestForAlreadyLockedAccount() throws Exception {
        // Given: Account is already locked (simulated by calling lock twice)
        // First lock succeeds
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))
                .andExpect(status().isOk());

        // When: POST /admin/accounts/{id}/lock again
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 10e: Unlock account without Keycloak integration should return 400.
     */
    @Test
    @DisplayName("Should reject unlock request for account without Keycloak integration")
    void shouldRejectUnlockRequestForAccountWithoutKeycloakIntegration() throws Exception {
        // Account from test-data.sql without keycloak_user_id
        String accountWithoutKeycloak = "0199bab1-fad2-bf76-c478-eae1f61e1c17";

        // When: POST /admin/accounts/{id}/unlock for account without Keycloak
        mockMvc.perform(post("/api/admin/accounts/{id}/unlock", accountWithoutKeycloak)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 10f: Unlock already unlocked account should return 400.
     */
    @Test
    @DisplayName("Should reject unlock request for already unlocked account")
    void shouldRejectUnlockRequestForAlreadyUnlockedAccount() throws Exception {
        // When: POST /admin/accounts/{id}/unlock for already unlocked account
        mockMvc.perform(post("/api/admin/accounts/{id}/unlock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 400 Bad Request (assuming account starts unlocked)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 10g: Lock non-existent account should return 404.
     */
    @Test
    @DisplayName("Should return 404 when locking non-existent account")
    void shouldReturn404WhenLockingNonExistentAccount() throws Exception {
        String nonExistentAccountId = "00000000-0000-0000-0000-000000000000";

        // When: POST /admin/accounts/{id}/lock for non-existent account
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", nonExistentAccountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 404 Not Found
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 10h: Unlock non-existent account should return 404.
     */
    @Test
    @DisplayName("Should return 404 when unlocking non-existent account")
    void shouldReturn404WhenUnlockingNonExistentAccount() throws Exception {
        String nonExistentAccountId = "00000000-0000-0000-0000-000000000000";

        // When: POST /admin/accounts/{id}/unlock for non-existent account
        mockMvc.perform(post("/api/admin/accounts/{id}/unlock", nonExistentAccountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 404 Not Found
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 10i: Non-admin user should not be able to lock account.
     */
    @Test
    @DisplayName("Should reject lock request when user lacks ROLE_ADMIN")
    void shouldRejectLockRequestWhenUserLacksRoleAdmin() throws Exception {
        // When: POST /admin/accounts/{id}/lock with non-admin token
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))

                // Then: 403 Forbidden
                .andExpect(status().isForbidden());
    }

    /**
     * Test Case 10j: Non-admin user should not be able to unlock account.
     */
    @Test
    @DisplayName("Should reject unlock request when user lacks ROLE_ADMIN")
    void shouldRejectUnlockRequestWhenUserLacksRoleAdmin() throws Exception {
        // When: POST /admin/accounts/{id}/unlock with non-admin token
        mockMvc.perform(post("/api/admin/accounts/{id}/unlock", MOCK_ACCOUNT_ID)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))

                // Then: 403 Forbidden
                .andExpect(status().isForbidden());
    }

    // ========== Batch Management Tests ==========

    /**
     * Test Case 11: List batches with filtering should return paginated results.
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

    // ========== Password Reset Tests (T051 - User Story 3) ==========

    /**
     * Test Case 17: Reset password should return 200 with temporary password and expiration.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: POST /admin/accounts/{id}/reset-password for account with Keycloak integration
     * Then: 200 OK with temporaryPassword and expiresAt
     * </p>
     */
    @Test
    @DisplayName("Should reset password and return temporary password when account has Keycloak")
    void shouldResetPasswordWhenAccountHasKeycloak() throws Exception {
        // Given: Account exists with Keycloak integration (from test-data.sql)
        String accountId = MOCK_ACCOUNT_ID;

        // When: POST /admin/accounts/{id}/reset-password
        mockMvc.perform(post("/api/admin/accounts/{id}/reset-password", accountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.temporaryPassword").exists())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    /**
     * Test Case 18: Reset password on account without Keycloak should return 400.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: POST /admin/accounts/{id}/reset-password for account WITHOUT Keycloak integration
     * Then: 400 Bad Request
     * </p>
     */
    @Test
    @DisplayName("Should reject password reset when account has no Keycloak integration")
    void shouldRejectPasswordResetWhenNoKeycloakIntegration() throws Exception {
        // Given: Account from test-data.sql without keycloak_user_id
        String accountWithoutKeycloak = "0199bab1-fad2-bf76-c478-eae1f61e1c17";

        // When: POST /admin/accounts/{id}/reset-password for account without Keycloak
        mockMvc.perform(post("/api/admin/accounts/{id}/reset-password", accountWithoutKeycloak)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Keycloak")));
    }

    /**
     * Test Case 19: Reset password on locked account should still work.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN AND account is locked
     * When: POST /admin/accounts/{id}/reset-password
     * Then: 200 OK with temporaryPassword (password reset works even on locked accounts)
     * </p>
     */
    @Test
    @DisplayName("Should reset password even when account is locked")
    void shouldResetPasswordWhenAccountIsLocked() throws Exception {
        // Given: Account exists with Keycloak integration
        String accountId = MOCK_ACCOUNT_ID;

        // Lock the account first
        mockMvc.perform(post("/api/admin/accounts/{id}/lock", accountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))
                .andExpect(status().isOk());

        // When: POST /admin/accounts/{id}/reset-password on locked account
        mockMvc.perform(post("/api/admin/accounts/{id}/reset-password", accountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 200 OK (password reset still works on locked accounts)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.temporaryPassword").exists())
                .andExpect(jsonPath("$.expiresAt").exists());

        // Unlock account for cleanup
        mockMvc.perform(post("/api/admin/accounts/{id}/unlock", accountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))
                .andExpect(status().isOk());
    }

    /**
     * Test Case 20: Reset password with non-existent account should return 404.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: POST /admin/accounts/{id}/reset-password with non-existent account ID
     * Then: 404 Not Found
     * </p>
     */
    @Test
    @DisplayName("Should return 404 when resetting password for non-existent account")
    void shouldReturn404WhenResetPasswordForNonExistentAccount() throws Exception {
        // Given: Non-existent account ID
        String nonExistentAccountId = "00000000-0000-0000-0000-000000000000";

        // When: POST /admin/accounts/{id}/reset-password
        mockMvc.perform(post("/api/admin/accounts/{id}/reset-password", nonExistentAccountId)
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN))

                // Then: 404 Not Found
                .andExpect(status().isNotFound());
    }

    /**
     * Test Case 21: Reset password without admin role should return 403.
     * <p>
     * Given: User authenticated WITHOUT ROLE_ADMIN
     * When: POST /admin/accounts/{id}/reset-password
     * Then: 403 Forbidden
     * </p>
     */
    @Test
    @DisplayName("Should reject password reset when user lacks ROLE_ADMIN")
    void shouldRejectPasswordResetWhenUserLacksRoleAdmin() throws Exception {
        // Given: Account exists
        String accountId = MOCK_ACCOUNT_ID;

        // When: POST /admin/accounts/{id}/reset-password with non-admin token
        mockMvc.perform(post("/api/admin/accounts/{id}/reset-password", accountId)
                        .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN))

                // Then: 403 Forbidden
                .andExpect(status().isForbidden());
    }

    // ========== Search Functionality Tests (PR #11 fixes) ==========

    /**
     * Test Case 22: List accounts with Keycloak integration without search should return all Keycloak accounts.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak without search parameter
     * Then: 200 OK with paginated list of all accounts with Keycloak integration
     * </p>
     */
    @Test
    @DisplayName("Should list all Keycloak accounts without search filter")
    void shouldListAllKeycloakAccountsWithoutSearch() throws Exception {
        // When: GET /admin/accounts/with-keycloak
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with paginated response
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].email").exists())
                .andExpect(jsonPath("$.content[0].keycloakUserId").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    /**
     * Test Case 23: Search accounts by email should filter results.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak?search=admin-test
     * Then: 200 OK with filtered results matching "admin-test" in email
     * </p>
     */
    @Test
    @DisplayName("Should filter Keycloak accounts by email search")
    void shouldFilterKeycloakAccountsByEmailSearch() throws Exception {
        // When: GET /admin/accounts/with-keycloak?search=admin-test
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("search", "admin-test")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with filtered results
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.email == 'admin-test@example.com')]").exists())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * Test Case 24: Search accounts by name should filter results.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak?search=Admin
     * Then: 200 OK with filtered results matching "Admin" in name
     * </p>
     */
    @Test
    @DisplayName("Should filter Keycloak accounts by name search")
    void shouldFilterKeycloakAccountsByNameSearch() throws Exception {
        // When: GET /admin/accounts/with-keycloak?search=Admin
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("search", "Admin")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with filtered results
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.name =~ /.*Admin.*/i)]").exists());
    }

    /**
     * Test Case 25: Search with no matches should return empty array.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak?search=nonexistent
     * Then: 200 OK with empty content array
     * </p>
     */
    @Test
    @DisplayName("Should return empty results when search matches no accounts")
    void shouldReturnEmptyResultsWhenSearchMatchesNothing() throws Exception {
        // When: GET /admin/accounts/with-keycloak?search=nonexistent
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("search", "nonexistent-user-xyz")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with empty content
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Test Case 26: Search with invalid characters should return 400.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak?search=test<script>
     * Then: 400 Bad Request due to validation failure
     * </p>
     */
    @Test
    @DisplayName("Should reject search with invalid characters")
    void shouldRejectSearchWithInvalidCharacters() throws Exception {
        // When: GET /admin/accounts/with-keycloak?search=test<script>
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("search", "test<script>")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Test Case 27: Search with length > 100 characters should return 400.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak?search=<101+ chars>
     * Then: 400 Bad Request due to length validation failure
     * </p>
     */
    @Test
    @DisplayName("Should reject search query exceeding 100 characters")
    void shouldRejectSearchQueryExceeding100Characters() throws Exception {
        // Given: Search query with 101 characters
        String longSearch = "a".repeat(101);

        // When: GET /admin/accounts/with-keycloak?search=<101 chars>
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("search", longSearch)
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("100")));
    }

    /**
     * Test Case 28: Search should be case-insensitive.
     * <p>
     * Given: Admin authenticated with ROLE_ADMIN
     * When: GET /admin/accounts/with-keycloak?search=ADMIN (uppercase)
     * Then: 200 OK with results matching "admin" (lowercase in DB)
     * </p>
     */
    @Test
    @DisplayName("Should perform case-insensitive search")
    void shouldPerformCaseInsensitiveSearch() throws Exception {
        // When: GET /admin/accounts/with-keycloak?search=ADMIN (uppercase)
        mockMvc.perform(get("/api/admin/accounts/with-keycloak")
                        .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                        .param("search", "ADMIN")
                        .param("page", "0")
                        .param("size", "20"))

                // Then: 200 OK with results (case-insensitive match)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.name =~ /.*admin.*/i)]").exists());
    }
}
