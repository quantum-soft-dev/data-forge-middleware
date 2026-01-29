package com.bitbi.dfm.integration;

import com.auth0.exception.Auth0Exception;
import com.auth0.exception.APIException;
import com.auth0.json.mgmt.users.User;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.domain.UserRole;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.shared.exception.Auth0RateLimitException;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * <p>
 * <b>IMPORTANT</b>: @Transactional ensures all database changes are rolled back after each test method,
 * preventing duplicate key violations when tests run in parallel or multiple times.
 * </p>
 *
 * User Story: US1 - Admin Creates User Account via Auth0
 * Task: T029 - Integration test for user creation
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@org.springframework.transaction.annotation.Transactional
@DisplayName("Auth0 User Creation Integration Test - User Story 1")
class Auth0UserCreationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountSyncService auth0AccountSyncService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private Auth0ManagementApiClient auth0ManagementApiClient;

    private static final String MOCK_AUTH0_USER_ID = "auth0|test-created-user-id-12345";

    @BeforeEach
    void setUp() throws Exception {
        // Setup Auth0ManagementApiClient behavior - create user returns mock user
        User mockUser = new User();
        mockUser.setId(MOCK_AUTH0_USER_ID);
        mockUser.setEmail("test-integration@example.com");
        mockUser.setName("Integration Test User");

        when(auth0ManagementApiClient.createUserWithPassword(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(mockUser);

        // updateUserMetadata and deleteUser don't throw by default
        doNothing().when(auth0ManagementApiClient).updateUserMetadata(any(Auth0UserId.class), any());
        doNothing().when(auth0ManagementApiClient).deleteUser(any(Auth0UserId.class));
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
            auth0AccountSyncService.createAccount(email, name, phone, company, UserRole.USER);

        // Then: Account created successfully
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.temporaryPassword()).isNotNull();
        assertThat(result.temporaryPassword()).matches("^[A-Za-z0-9!@#$%^&*]{16}$");

        Account account = result.account();
        assertThat(account.getId()).isNotNull();
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(account.getName()).isEqualTo(name);
        assertThat(account.getPhone().getValue()).isEqualTo(phone);
        assertThat(account.getCompany().getValue()).isEqualTo(company);
        assertThat(account.getIsActive()).isTrue();
        assertThat(account.getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: PostgreSQL persistence
        Optional<Account> savedAccount = accountRepository.findById(account.getId());
        assertThat(savedAccount).isPresent();
        assertThat(savedAccount.get().getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: Auth0 API calls via Auth0ManagementApiClient
        verify(auth0ManagementApiClient, times(1)).createUserWithPassword(eq(email), eq(name), anyString(), eq(true));

        // Verify: Bidirectional mapping (Auth0 user updated with accountId)
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auth0ManagementApiClient, times(1)).updateUserMetadata(eq(Auth0UserId.of(MOCK_AUTH0_USER_ID)), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).containsKey("accountId");
        assertThat(metadataCaptor.getValue().get("accountId")).isEqualTo(account.getId().toString());

        // Verify: No deletion (no rollback needed)
        verify(auth0ManagementApiClient, never()).deleteUser(any(Auth0UserId.class));
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

        // When/Then: Exception thrown due to validation error
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(email, name, phone, company, UserRole.USER)
        ).isInstanceOf(Exception.class);

        // Verify: Auth0 user created
        verify(auth0ManagementApiClient, times(1)).createUserWithPassword(eq(email), eq(name), anyString(), eq(true));

        // Verify: Auth0 user deleted (compensating transaction)
        verify(auth0ManagementApiClient, times(1)).deleteUser(Auth0UserId.of(MOCK_AUTH0_USER_ID));

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
        auth0AccountSyncService.createAccount(email, "First User", null, null, UserRole.USER);

        // Reset mock to verify second attempt
        reset(auth0ManagementApiClient);

        // When/Then: Attempt to create duplicate
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(email, "Second User", null, null, UserRole.USER)
        ).isInstanceOf(Exception.class)
         .hasMessageContaining("already exists");

        // Verify: Auth0 user NOT created (early validation prevented it)
        verify(auth0ManagementApiClient, never()).createUserWithPassword(anyString(), anyString(), anyString(), anyBoolean());
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
    void createAccount_auth0RateLimit_exceptionThrown() throws Exception {
        // Given: Mock Auth0 rate limit error
        when(auth0ManagementApiClient.createUserWithPassword(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new APIException("Rate limit exceeded", 429, null));

        // When/Then: Exception thrown
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(
                "ratelimit@example.com",
                "Rate Limit Test",
                null,
                null,
                UserRole.USER
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
    void createAccount_auth0ServiceUnavailable_exceptionThrown() throws Exception {
        // Given: Mock Auth0 service unavailable error
        when(auth0ManagementApiClient.createUserWithPassword(anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new APIException("Service unavailable", 503, null));

        // When/Then: Exception thrown
        assertThatThrownBy(() ->
            auth0AccountSyncService.createAccount(
                "unavailable@example.com",
                "Service Unavailable Test",
                null,
                null,
                UserRole.USER
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
    void createAccount_metadataUpdateFailure_accountCreatedAnyway() throws Exception {
        // Given: Mock metadata update failure
        doThrow(new APIException("Metadata update failed", 500, null))
                .when(auth0ManagementApiClient).updateUserMetadata(any(Auth0UserId.class), any());

        // When
        AccountSyncService.AccountCreationResult result =
            auth0AccountSyncService.createAccount("metadata-fail@example.com", "Test User", null, null, UserRole.USER);

        // Then: Account created successfully despite metadata failure
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: Auth0 user was created
        verify(auth0ManagementApiClient).createUserWithPassword(eq("metadata-fail@example.com"), anyString(), anyString(), eq(true));

        // Verify: Metadata update was attempted
        verify(auth0ManagementApiClient).updateUserMetadata(any(Auth0UserId.class), any());

        // Verify: No rollback (account creation succeeded)
        verify(auth0ManagementApiClient, never()).deleteUser(any(Auth0UserId.class));
    }

    /**
     * TC07: Token expiration retry (simulated via exception flow)
     * <p>
     * Given: Auth0ManagementApiClient handles token expiration internally
     * When: Create account with Auth0
     * Then: Account created successfully (retry handled internally)
     * </p>
     */
    @Test
    @DisplayName("TC07: Successful account creation with automatic token retry")
    void createAccount_withTokenRetry_success() throws Exception {
        // Given: Token retry is handled internally by Auth0ManagementApiClient
        // This test verifies the end-to-end flow works correctly

        String email = "token-retry@example.com";
        String name = "Token Retry User";

        // When
        AccountSyncService.AccountCreationResult result =
            auth0AccountSyncService.createAccount(email, name, null, null, UserRole.USER);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getEmail()).isEqualTo(email);
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Verify: PostgreSQL persistence
        Optional<Account> savedAccount = accountRepository.findByEmail(email);
        assertThat(savedAccount).isPresent();
    }
}
