package com.bitbi.dfm.integration;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.UsersEntity;
import com.auth0.exception.Auth0Exception;
import com.auth0.exception.APIException;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.shared.exception.Auth0RateLimitException;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration test for Auth0 user creation with bidirectional linkage.
 * <p>
 * Tests the two-phase commit pattern for account creation:
 * 1. Create user in Auth0 (authentication layer)
 * 2. Create account in PostgreSQL (business layer) with auth0UserId
 * 3. Update Auth0 user with PostgreSQL accountId (bidirectional mapping)
 * 4. If PostgreSQL fails, delete Auth0 user (rollback compensation)
 * </p>
 *
 * User Story: US1 - Admin Creates User Account via Auth0
 * Task: T029 - Integration test for user creation
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@SpringBootTest(properties = {
    "auth0.database-connection=Username-Password-Authentication",
    "auth0.domain=test.auth0.com",
    "auth0.api.audience=https://test-api.example.com"
})
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@DisplayName("Auth0 User Creation Integration Test - User Story 1")
class Auth0UserCreationIntegrationTest {

    @Autowired
    private AccountSyncService auth0AccountSyncService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private ManagementAPI managementAPI;

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
            createdUser.setEmail("test-integration@example.com");
            createdUser.setName("Integration Test User");

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
     * TC01: Successful account creation with bidirectional linkage
     * <p>
     * Given: Valid account details
     * When: Create account with Auth0
     * Then: Auth0 user created
     * And: PostgreSQL account created with auth0UserId
     * And: Auth0 user updated with PostgreSQL accountId
     * And: Temporary password returned
     * </p>
     */
    @Test
    @DisplayName("TC01: Successful account creation with bidirectional linkage")
    void createAccount_success_bidirectionalLinkage() throws Exception {
        // Given
        String email = "john.doe@example.com";
        String name = "John Doe";
        String phone = "+12345678901";
        String company = "Acme Corp";

        // When
        AccountSyncService.AccountCreationResult result =
            auth0AccountSyncService.createAccount(email, name, phone, company);

        // Then: Account created successfully
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.temporaryPassword()).isNotNull();
        assertThat(result.temporaryPassword()).matches("^[A-Za-z0-9!@#$%^&*]{16}$");

        Account account = result.account();
        assertThat(account.getId()).isNotNull();
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(account.getName()).isEqualTo(name);
        assertThat(account.getPhone()).isEqualTo(phone);
        assertThat(account.getCompany()).isEqualTo(company);
        assertThat(account.getIsActive()).isTrue();
        assertThat(account.getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: PostgreSQL persistence
        Optional<Account> savedAccount = accountRepository.findById(account.getId());
        assertThat(savedAccount).isPresent();
        assertThat(savedAccount.get().getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: Auth0 API calls
        verify(managementAPI.users(), times(1)).create(any(User.class));
        verify(managementAPI.users(), times(1)).update(eq(MOCK_AUTH0_USER_ID), any(User.class));
        verify(managementAPI.users(), never()).delete(anyString());

        // Verify: Bidirectional mapping (Auth0 user updated with accountId)
        verify(managementAPI.users()).update(eq(MOCK_AUTH0_USER_ID), argThat(user -> {
            Map<String, Object> appMetadata = user.getAppMetadata();
            return appMetadata != null &&
                   appMetadata.containsKey("accountId") &&
                   appMetadata.get("accountId").equals(account.getId().toString());
        }));
    }

    /**
     * TC02: Rollback Auth0 user on PostgreSQL failure
     * <p>
     * Given: Valid account details
     * And: PostgreSQL save will fail (simulated)
     * When: Create account with Auth0
     * Then: Auth0 user created
     * But: PostgreSQL save fails
     * And: Auth0 user deleted (compensating transaction)
     * And: Exception thrown
     * </p>
     */
    @Test
    @DisplayName("TC02: Rollback Auth0 user on PostgreSQL failure")
    void createAccount_postgresqlFailure_rollbackAuth0User() throws Exception {
        // Given: Force PostgreSQL failure by exceeding max length
        String email = "test@example.com";
        String name = "Test User";
        String phone = "+12345678901";
        String company = "A".repeat(300); // Exceeds max length (200), will cause validation error

        // When/Then: Exception thrown
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(email, name, phone, company)
        ).isInstanceOf(Exception.class);

        // Verify: Auth0 user created
        verify(managementAPI.users(), times(1)).create(any(User.class));

        // Verify: Auth0 user deleted (compensating transaction)
        verify(managementAPI.users(), times(1)).delete(eq(MOCK_AUTH0_USER_ID));

        // Verify: No account created in PostgreSQL
        Optional<Account> account = accountRepository.findByEmail(email);
        assertThat(account).isEmpty();
    }

    /**
     * TC03: Account already exists - no Auth0 user created
     * <p>
     * Given: Account with email already exists in PostgreSQL
     * When: Create account with Auth0
     * Then: AccountAlreadyExistsException thrown
     * And: Auth0 user NOT created (early validation)
     * </p>
     */
    @Test
    @DisplayName("TC03: Account already exists - no Auth0 user created")
    void createAccount_accountExists_noAuth0UserCreated() throws Exception {
        // Given: Create first account
        String email = "duplicate@example.com";
        auth0AccountSyncService.createAccount(email, "First User", null, null);

        // Reset mock to verify second attempt
        reset(managementAPI.users());
        when(managementAPI.users()).thenReturn(mockUsersEntity);

        // When/Then: Attempt to create duplicate
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(email, "Second User", null, null)
        ).isInstanceOf(Exception.class)
         .hasMessageContaining("already exists");

        // Verify: Auth0 user NOT created (early validation prevented it)
        verify(managementAPI.users(), never()).create(any(User.class));
    }

    /**
     * TC04: Auth0 rate limit handling
     * <p>
     * Given: Auth0 returns 429 Too Many Requests
     * When: Create account with Auth0
     * Then: Auth0RateLimitException thrown
     * </p>
     */
    @Test
    @DisplayName("TC04: Auth0 rate limit - exception thrown")
    @SuppressWarnings("unchecked")
    void createAccount_auth0RateLimit_exceptionThrown() throws Exception {
        // Given: Mock Auth0 rate limit error
        Request<User> failingRequest = mock(Request.class);
        when(mockUsersEntity.create(any(User.class))).thenReturn(failingRequest);
        when(failingRequest.execute()).thenThrow(new APIException("Rate limit exceeded", 429, null));

        // When/Then: Exception thrown
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(
                "ratelimit@example.com",
                "Rate Limit Test",
                null,
                null
            )
        ).isInstanceOf(Auth0RateLimitException.class);

        // Verify: No account created in PostgreSQL
        Optional<Account> account = accountRepository.findByEmail("ratelimit@example.com");
        assertThat(account).isEmpty();
    }

    /**
     * TC05: Auth0 service unavailable (5xx error)
     * <p>
     * Given: Auth0 returns 503 Service Unavailable
     * When: Create account with Auth0
     * Then: Auth0ServiceUnavailableException thrown
     * </p>
     */
    @Test
    @DisplayName("TC05: Auth0 service unavailable - exception thrown")
    @SuppressWarnings("unchecked")
    void createAccount_auth0ServiceUnavailable_exceptionThrown() throws Exception {
        // Given: Mock Auth0 service unavailable error
        Request<User> failingRequest = mock(Request.class);
        when(mockUsersEntity.create(any(User.class))).thenReturn(failingRequest);
        when(failingRequest.execute()).thenThrow(new APIException("Service unavailable", 503, null));

        // When/Then: Exception thrown
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(
                "unavailable@example.com",
                "Service Unavailable Test",
                null,
                null
            )
        ).isInstanceOf(Auth0ServiceUnavailableException.class);

        // Verify: No account created in PostgreSQL
        Optional<Account> account = accountRepository.findByEmail("unavailable@example.com");
        assertThat(account).isEmpty();
    }

    /**
     * TC06: Metadata update failure handled gracefully
     * <p>
     * Given: Auth0 user created successfully
     * But: Metadata update fails (non-critical)
     * When: Create account with Auth0
     * Then: Account creation succeeds
     * And: Warning logged (metadata update is non-critical)
     * </p>
     */
    @Test
    @DisplayName("TC06: Metadata update failure handled gracefully")
    @SuppressWarnings("unchecked")
    void createAccount_metadataUpdateFailure_accountCreated() throws Exception {
        // Given: Mock metadata update failure
        Request<User> failingUpdateRequest = mock(Request.class);
        when(mockUsersEntity.update(anyString(), any(User.class))).thenReturn(failingUpdateRequest);
        when(failingUpdateRequest.execute()).thenThrow(new APIException("Metadata update failed", 400, null));

        // When: Create account (should succeed despite metadata failure)
        String email = "metadata-failure@example.com";
        AccountSyncService.AccountCreationResult result =
            auth0AccountSyncService.createAccount(email, "Metadata Test", null, null);

        // Then: Account created successfully
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: PostgreSQL account exists
        Optional<Account> account = accountRepository.findByEmail(email);
        assertThat(account).isPresent();
        assertThat(account.get().getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: Auth0 user created and metadata update attempted
        verify(managementAPI.users(), times(1)).create(any(User.class));
        verify(managementAPI.users(), times(1)).update(eq(MOCK_AUTH0_USER_ID), any(User.class));

        // Verify: NO rollback (account creation succeeded)
        verify(managementAPI.users(), never()).delete(anyString());
    }

    /**
     * TC07: Optional fields (phone, company) handled correctly
     * <p>
     * Given: Account details with null phone and company
     * When: Create account with Auth0
     * Then: Account created with null optional fields
     * And: Auth0 user created successfully
     * </p>
     */
    @Test
    @DisplayName("TC07: Optional fields handled correctly")
    void createAccount_optionalFields_handledCorrectly() throws Exception {
        // Given
        String email = "optional-fields@example.com";
        String name = "Optional Fields Test";

        // When
        AccountSyncService.AccountCreationResult result =
            auth0AccountSyncService.createAccount(email, name, null, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getPhone()).isNull();
        assertThat(result.account().getCompany()).isNull();

        // Verify: PostgreSQL persistence
        Optional<Account> savedAccount = accountRepository.findById(result.account().getId());
        assertThat(savedAccount).isPresent();
        assertThat(savedAccount.get().getPhone()).isNull();
        assertThat(savedAccount.get().getCompany()).isNull();
    }
}
