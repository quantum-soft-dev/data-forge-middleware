package com.bitbi.dfm.contract;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.UsersEntity;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.bitbi.dfm.account.application.AccountSyncService;
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
@DisplayName("Auth0 Admin API Contract Tests - User Story 1")
class Auth0AdminContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ManagementAPI managementAPI;

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
            .andExpect(jsonPath("$.auth0UserId").value(MOCK_AUTH0_USER_ID))
            .andExpect(jsonPath("$.temporaryPassword").exists())
            .andExpect(jsonPath("$.temporaryPassword").isString())
            .andExpect(jsonPath("$.temporaryPassword").value(not(emptyString())))
            .andExpect(jsonPath("$.passwordResetUrl").value(nullValue()))
            .andExpect(jsonPath("$.createdAt").exists());

        // Verify Auth0 Management API was called
        verify(managementAPI.users(), times(1)).create(any(User.class));
        verify(managementAPI.users(), times(1)).update(eq(MOCK_AUTH0_USER_ID), any(User.class));
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

        // Verify Auth0 Management API was NOT called
        verify(managementAPI.users(), never()).create(any(User.class));
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

        // Verify Auth0 Management API was NOT called (early validation)
        verify(managementAPI.users(), never()).create(any(User.class));
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

        // Verify Auth0 Management API was NOT called
        verify(managementAPI.users(), never()).create(any(User.class));
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

        // Verify Auth0 Management API was NOT called
        verify(managementAPI.users(), never()).create(any(User.class));
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

        // Verify Auth0 Management API was NOT called
        verify(managementAPI.users(), never()).create(any(User.class));
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

        // Verify Auth0 Management API was NOT called
        verify(managementAPI.users(), never()).create(any(User.class));
    }
}
