package com.bitbi.dfm.contract;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.UsersEntity;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.auth.application.Auth0TokenProvider;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.shared.api.ApiRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for Auth0 Admin API endpoints.
 * <p>
 * CRITICAL: These tests MUST FAIL before implementation.
 * Purpose: Validate admin operations with Auth0 integration.
 * </p>
 *
 * User Story: US1 - Admin Creates User Account via Auth0
 *
 * @see <a href="specs/011-auth0-migration-migrate/contracts/admin-api-auth0.openapi.yaml">Auth0 Admin API Contract</a>
 * @author Data Forge Team
 * @version 1.0.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Auth0 Admin API Contract Tests - User Story 1")
class Auth0AdminContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ManagementAPI managementAPI;

    @MockitoBean
    private Auth0ManagementApiClient auth0Client;

    @MockitoBean
    private Auth0TokenProvider auth0TokenProvider;

    @MockitoBean
    private AccountSyncService accountSyncService;

    private static final String MOCK_ADMIN_JWT_TOKEN = "mock.admin.jwt.token";
    private static final String MOCK_USER_JWT_TOKEN = "mock.user.jwt.token";
    private static final String MOCK_AUTH0_USER_ID = "auth0|60f7b8a8b4a0f10074c5d0e1";

    private UsersEntity mockUsersEntity;
    private Request<User> mockCreateUserRequest;
    private Request<User> mockUpdateUserRequest;
    private Request<Void> mockDeleteUserRequest;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // Create mock entities for ManagementAPI
        mockUsersEntity = mock(UsersEntity.class);
        mockCreateUserRequest = mock(Request.class);
        mockUpdateUserRequest = mock(Request.class);
        mockDeleteUserRequest = mock(Request.class);

        // Setup ManagementAPI behavior
        when(managementAPI.users()).thenReturn(mockUsersEntity);

        // Mock user creation - returns new Auth0 user
        when(mockUsersEntity.create(any(User.class))).thenReturn(mockCreateUserRequest);
        when(mockCreateUserRequest.execute()).thenAnswer((Answer<com.auth0.net.Response<User>>) invocation -> {
            User createdUser = new User();
            createdUser.setId(MOCK_AUTH0_USER_ID);
            createdUser.setEmail("test@example.com");
            createdUser.setName("Test User");

            com.auth0.net.Response<User> response = mock(com.auth0.net.Response.class);
            when(response.getBody()).thenReturn(createdUser);
            return response;
        });

        // Mock user update (for app_metadata)
        when(mockUsersEntity.update(anyString(), any(User.class))).thenReturn(mockUpdateUserRequest);
        when(mockUpdateUserRequest.execute()).thenAnswer((Answer<com.auth0.net.Response<User>>) invocation -> {
            User updatedUser = new User();
            updatedUser.setId(MOCK_AUTH0_USER_ID);

            com.auth0.net.Response<User> response = mock(com.auth0.net.Response.class);
            when(response.getBody()).thenReturn(updatedUser);
            return response;
        });

        // Mock user deletion (for compensating transactions)
        when(mockUsersEntity.delete(anyString())).thenReturn(mockDeleteUserRequest);
        when(mockDeleteUserRequest.execute()).thenReturn(null);

        // Mock Auth0ManagementApiClient for lock/unlock operations
        doNothing().when(auth0Client).blockUser(any(Auth0UserId.class));
        doNothing().when(auth0Client).unblockUser(any(Auth0UserId.class));

        // Mock Auth0TokenProvider to prevent real HTTP calls for token generation
        when(auth0TokenProvider.getAccessToken()).thenReturn("mock-management-api-token");

        // Mock AccountSyncService.createAccount() - use doAnswer with nullable parameters
        doAnswer(invocation -> {
            String email = invocation.getArgument(0);
            String name = invocation.getArgument(1);
            String phone = invocation.getArgument(2);
            String company = invocation.getArgument(3);

            // Simulate duplicate email check (admin@dataforge.com exists in test-data.sql)
            if ("admin@dataforge.com".equals(email)) {
                throw new com.bitbi.dfm.account.application.AccountService.AccountAlreadyExistsException(
                    "Account with email " + email + " already exists"
                );
            }

            // Create a minimal mock account for testing the response format
            com.bitbi.dfm.account.domain.Account mockAccount =
                com.bitbi.dfm.account.domain.Account.createWithIdentityProvider(
                    MOCK_AUTH0_USER_ID,
                    email,
                    name,
                    phone,
                    company
                );

            return new AccountSyncService.AccountCreationResult(
                mockAccount,
                "TempPass123!"
            );
        }).when(accountSyncService).createAccount(anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    /**
     * TC01: Valid request returns 201 with temporary password
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/accounts with valid request body
     * Then: Returns 201 Created with account details and temporary password
     * And: Auth0 user ID is populated
     * </p>
     */
    @Test
    @DisplayName("TC01: Create account with Auth0 - valid request returns 201 with temporaryPassword")
    void createAccount_validRequest_returns201WithTemporaryPassword() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "john.doe@example.com",
            "name", "John Doe",
            "phone", "+12345678901",
            "company", "Acme Corp"
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.email").value("john.doe@example.com"))
            .andExpect(jsonPath("$.name").value("John Doe"))
            .andExpect(jsonPath("$.phone").value("+12345678901"))
            .andExpect(jsonPath("$.company").value("Acme Corp"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.identityProviderUserId").value(MOCK_AUTH0_USER_ID))
            .andExpect(jsonPath("$.temporaryPassword").exists())
            .andExpect(jsonPath("$.temporaryPassword").isString())
            .andExpect(jsonPath("$.temporaryPassword").value(not(emptyString())))
            .andExpect(jsonPath("$.passwordResetUrl").value(nullValue()))
            .andExpect(jsonPath("$.createdAt").exists());

        // Verify AccountSyncService.createAccount() was called with correct parameters
        verify(accountSyncService, times(1)).createAccount(
            eq("john.doe@example.com"),
            eq("John Doe"),
            eq("+12345678901"),
            eq("Acme Corp")
        );
    }

    /**
     * TC02: Invalid email returns 400
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/accounts with invalid email format
     * Then: Returns 400 Bad Request with validation error
     * </p>
     */
    @Test
    @DisplayName("TC02: Create account with Auth0 - invalid email returns 400")
    void createAccount_invalidEmail_returns400() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "not-an-email",
            "name", "John Doe"
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value(containsString("Email")));

        // Verify AccountSyncService was NOT called (validation failed before service call)
        verify(accountSyncService, never()).createAccount(anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    /**
     * TC03: Duplicate email returns 409
     * <p>
     * Given: Admin authenticated with valid JWT
     * And: Account with email already exists in PostgreSQL
     * When: POST /api/v1/accounts with duplicate email
     * Then: Returns 409 Conflict
     * </p>
     */
    @Test
    @DisplayName("TC03: Create account with Auth0 - duplicate email returns 409")
    @Sql("/test-data.sql") // Ensure test account exists
    void createAccount_duplicateEmail_returns409() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "admin@dataforge.com", // Exists in test-data.sql
            "name", "Duplicate User"
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.error").value("Conflict"))
            .andExpect(jsonPath("$.message").value(containsString("already exists")));

        // Verify AccountSyncService was called and threw AccountAlreadyExistsException
        verify(accountSyncService, times(1)).createAccount(
            eq("admin@dataforge.com"),
            eq("Duplicate User"),
            isNull(),  // phone is null
            isNull()   // company is null
        );
    }

    /**
     * TC04: Missing required fields returns 400
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/accounts with missing required fields
     * Then: Returns 400 Bad Request with validation errors
     * </p>
     */
    @Test
    @DisplayName("TC04: Create account with Auth0 - missing required fields returns 400")
    void createAccount_missingRequiredFields_returns400() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "test@example.com"
            // Missing 'name' field
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value(containsString("Name")));

        // Verify AccountSyncService was NOT called (validation failed - missing required fields)
        verify(accountSyncService, never()).createAccount(anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    /**
     * TC05: Unauthenticated request returns 401
     * <p>
     * Given: No authentication header
     * When: POST /api/v1/accounts
     * Then: Returns 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC05: Create account with Auth0 - unauthenticated returns 401")
    void createAccount_unauthenticated_returns401() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "test@example.com",
            "name", "Test User"
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isUnauthorized());

        // Verify AccountSyncService was NOT called (no authentication)
        verify(accountSyncService, never()).createAccount(anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    /**
     * TC06: Non-admin user returns 403
     * <p>
     * Given: User authenticated with USER role (not ADMIN)
     * When: POST /api/v1/accounts
     * Then: Returns 403 Forbidden
     * </p>
     */
    @Test
    @DisplayName("TC06: Create account with Auth0 - non-admin returns 403")
    void createAccount_nonAdmin_returns403() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "test@example.com",
            "name", "Test User"
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isForbidden());

        // Verify AccountSyncService was NOT called (authorization failed - not admin)
        verify(accountSyncService, never()).createAccount(anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    /**
     * TC07: Invalid phone format returns 400
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/accounts with invalid phone format
     * Then: Returns 400 Bad Request with validation error
     * </p>
     */
    @Test
    @DisplayName("TC07: Create account with Auth0 - invalid phone format returns 400")
    void createAccount_invalidPhoneFormat_returns400() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "email", "test@example.com",
            "name", "Test User",
            "phone", "invalid-phone"
        );

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_CREATE)
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value(containsString("Phone")));

        // Verify AccountSyncService was NOT called (validation failed - invalid phone format)
        verify(accountSyncService, never()).createAccount(anyString(), anyString(), nullable(String.class), nullable(String.class));
    }

    // ==================== User Story 2: Lock/Unlock Accounts ====================

    /**
     * TC08: Lock account - successful lock returns 204
     * <p>
     * Given: Admin authenticated with valid JWT
     * And: Account exists in database with Auth0 integration
     * And: Admin is not locking their own account
     * When: POST /api/v1/admin/accounts/{id}/lock
     * Then: Returns 204 No Content
     * And: Auth0 user is blocked
     * </p>
     */
    @Test
    @DisplayName("TC08: Lock account - successful lock returns 204")
    void lockAccount_validRequest_returns204() throws Exception {
        // Use test account with Auth0 integration from test-data.sql
        // This is NOT the admin's own account (admin is a1b2c3d4-e5f6-7890-abcd-ef1234567890)
        String accountId = "0199bab1-fad2-bf76-c478-eae1f61e1c17"; // Test Account 2 (different from admin)

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_LOCK.replace("{id}", accountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Verify Auth0ManagementApiClient.blockUser was called with correct Auth0 user ID
        verify(auth0Client, times(1)).blockUser(argThat(userId ->
            userId != null && userId.value().equals(MOCK_AUTH0_USER_ID)
        ));
    }

    /**
     * TC09: Lock account - locking own account returns 403
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/admin/accounts/{id}/lock with admin's own account ID
     * Then: Returns 403 Forbidden with error message
     * </p>
     */
    @Test
    @DisplayName("TC09: Lock account - locking own account returns 403")
    void lockAccount_lockingOwnAccount_returns403() throws Exception {
        // Use admin's account ID from test-data.sql
        String adminAccountId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"; // Admin Test Account

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_LOCK.replace("{id}", adminAccountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.error").value("Forbidden"))
            .andExpect(jsonPath("$.message").value(containsString("cannot lock your own account")));

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).blockUser(any(Auth0UserId.class));
    }

    /**
     * TC10: Lock account - non-existent account returns 404
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/admin/accounts/{id}/lock with non-existent account ID
     * Then: Returns 404 Not Found
     * </p>
     */
    @Test
    @DisplayName("TC10: Lock account - non-existent account returns 404")
    void lockAccount_nonExistentAccount_returns404() throws Exception {
        String nonExistentAccountId = "00000000-0000-0000-0000-000000000000";

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_LOCK.replace("{id}", nonExistentAccountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").value(containsString("Account not found")));

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).blockUser(any(Auth0UserId.class));
    }

    /**
     * TC11: Unlock account - successful unlock returns 204
     * <p>
     * Given: Admin authenticated with valid JWT
     * And: Account exists in database with Auth0 integration
     * When: POST /api/v1/admin/accounts/{id}/unlock
     * Then: Returns 204 No Content
     * And: Auth0 user is unblocked
     * </p>
     */
    @Test
    @DisplayName("TC11: Unlock account - successful unlock returns 204")
    void unlockAccount_validRequest_returns204() throws Exception {
        String accountId = "0199bab1-fad2-bf76-c478-eae1f61e1c17"; // Test Account 2

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_UNLOCK.replace("{id}", accountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNoContent());

        // Verify Auth0ManagementApiClient.unblockUser was called with correct Auth0 user ID
        verify(auth0Client, times(1)).unblockUser(argThat(userId ->
            userId != null && userId.value().equals(MOCK_AUTH0_USER_ID)
        ));
    }

    /**
     * TC12: Unlock account - non-existent account returns 404
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/admin/accounts/{id}/unlock with non-existent account ID
     * Then: Returns 404 Not Found
     * </p>
     */
    @Test
    @DisplayName("TC12: Unlock account - non-existent account returns 404")
    void unlockAccount_nonExistentAccount_returns404() throws Exception {
        String nonExistentAccountId = "00000000-0000-0000-0000-000000000000";

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_UNLOCK.replace("{id}", nonExistentAccountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").value(containsString("Account not found")));

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).unblockUser(any(Auth0UserId.class));
    }

    /**
     * TC13: Lock account - unauthenticated returns 401
     * <p>
     * Given: No authentication header
     * When: POST /api/v1/admin/accounts/{id}/lock
     * Then: Returns 401 Unauthorized
     * </p>
     */
    @Test
    @DisplayName("TC13: Lock account - unauthenticated returns 401")
    void lockAccount_unauthenticated_returns401() throws Exception {
        String accountId = "0199bab1-fad2-bf76-c478-eae1f61e1c17";

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_LOCK.replace("{id}", accountId))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());

        // Verify Auth0ManagementApiClient was NOT called (no authentication)
        verify(auth0Client, never()).blockUser(any(Auth0UserId.class));
    }

    /**
     * TC14: Lock account - non-admin returns 403
     * <p>
     * Given: User authenticated with USER role (not ADMIN)
     * When: POST /api/v1/admin/accounts/{id}/lock
     * Then: Returns 403 Forbidden
     * </p>
     */
    @Test
    @DisplayName("TC14: Lock account - non-admin returns 403")
    void lockAccount_nonAdmin_returns403() throws Exception {
        String accountId = "0199bab1-fad2-bf76-c478-eae1f61e1c17";

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_LOCK.replace("{id}", accountId))
                .header("Authorization", "Bearer " + MOCK_USER_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());

        // Verify Auth0ManagementApiClient was NOT called (authorization failed - not admin)
        verify(auth0Client, never()).blockUser(any(Auth0UserId.class));
    }

    // ================================================================================
    // User Story 3: Admin Resets User Password
    // ================================================================================

    /**
     * TC15: Reset password - successful reset returns 200 with password reset link
     * <p>
     * Given: Admin authenticated with valid JWT
     * And: Account exists in database with Auth0 integration
     * When: POST /api/v1/admin/accounts/{id}/reset-password
     * Then: Returns 200 OK
     * And: Response contains passwordResetLink field
     * And: Auth0 password change ticket is created
     * </p>
     */
    @Test
    @DisplayName("TC15: Reset password - successful reset returns 200 with passwordResetLink")
    void resetPassword_validRequest_returns200WithResetLink() throws Exception {
        String accountId = "0199bab1-fad2-bf76-c478-eae1f61e1c17"; // Test Account 2
        String mockResetLink = "https://your-tenant.us.auth0.com/lo/reset?ticket=mockTicketToken123";

        // Mock Auth0ManagementApiClient to return password reset link
        when(auth0Client.generatePasswordResetLink(any(Auth0UserId.class), nullable(String.class)))
            .thenReturn(mockResetLink);

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_RESET_PASSWORD.replace("{id}", accountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordResetLink").value(mockResetLink))
            .andExpect(jsonPath("$.accountId").value(accountId))
            .andExpect(jsonPath("$.email").exists())
            .andExpect(jsonPath("$.expiresAt").exists());

        // Verify Auth0ManagementApiClient.generatePasswordResetLink was called with correct Auth0 user ID
        verify(auth0Client, times(1)).generatePasswordResetLink(
            argThat(userId -> userId != null && userId.value().equals(MOCK_AUTH0_USER_ID)),
            nullable(String.class)
        );
    }

    /**
     * TC16: Reset password - non-existent account returns 404
     * <p>
     * Given: Admin authenticated with valid JWT
     * When: POST /api/v1/admin/accounts/{id}/reset-password with non-existent account ID
     * Then: Returns 404 Not Found
     * </p>
     */
    @Test
    @DisplayName("TC16: Reset password - non-existent account returns 404")
    void resetPassword_nonExistentAccount_returns404() throws Exception {
        String nonExistentAccountId = "00000000-0000-0000-0000-000000000000";

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_RESET_PASSWORD.replace("{id}", nonExistentAccountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").value(containsString("Account not found")));

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).generatePasswordResetLink(any(Auth0UserId.class), nullable(String.class));
    }

    /**
     * TC17: Reset password - account without Auth0 integration returns 400
     * <p>
     * Given: Admin authenticated with valid JWT
     * And: Account exists but has no Auth0 user ID
     * When: POST /api/v1/admin/accounts/{id}/reset-password
     * Then: Returns 400 Bad Request
     * And: Error message indicates missing Auth0 integration
     * </p>
     */
    @Test
    @DisplayName("TC17: Reset password - account without Auth0 integration returns 400")
    void resetPassword_noAuth0Integration_returns400() throws Exception {
        // Use Inactive Account which has no Auth0 integration (identity_provider_user_id is NULL)
        String accountId = "0199bab2-3cbd-cc95-a989-57ba51d258c8"; // Inactive Account from test-data.sql

        mockMvc.perform(post(ApiRoutes.ACCOUNTS_RESET_PASSWORD.replace("{id}", accountId))
                .header("Authorization", "Bearer " + MOCK_ADMIN_JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.message").value(containsString("Auth0 integration")));

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).generatePasswordResetLink(any(Auth0UserId.class), nullable(String.class));
    }
}
