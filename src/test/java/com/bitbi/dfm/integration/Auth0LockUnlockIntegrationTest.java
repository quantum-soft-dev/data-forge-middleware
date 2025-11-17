package com.bitbi.dfm.integration;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.integration.AbstractIntegrationTest;
import com.bitbi.dfm.shared.exception.CannotLockOwnAccountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for Auth0 account lock/unlock operations.
 * <p>
 * Tests the Auth0 blocking functionality:
 * 1. Lock account - sets user.blocked = true in Auth0
 * 2. Unlock account - sets user.blocked = false in Auth0
 * 3. Prevent self-lock - admin cannot lock their own account
 * </p>
 *
 * User Story: US2 - Admin Locks/Unlocks User Accounts
 * Task: T040 - Integration test for lock/unlock
 *
 * @author Data Forge Team
 * @version 1.0.0
 */@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Auth0 Lock/Unlock Integration Test - User Story 2")
class Auth0LockUnlockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private Auth0ManagementApiClient auth0Client;

    @MockitoBean
    private AccountSyncService accountSyncService;

    private static final String MOCK_AUTH0_USER_ID = "auth0|60f7b8a8b4a0f10074c5d0e1";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("0199bab1-fad2-bf76-c478-eae1f61e1c17"); // Test Account 2 from test-data.sql
    private static final UUID ADMIN_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"); // Admin Test Account from test-data.sql

    @BeforeEach
    void setUp() throws Exception {
        // Mock Auth0ManagementApiClient methods to do nothing (successful execution)
        // blockUser and unblockUser return void, so we just need to ensure they don't throw exceptions
        doNothing().when(auth0Client).blockUser(any(Auth0UserId.class));
        doNothing().when(auth0Client).unblockUser(any(Auth0UserId.class));
    }

    /**
     * Test Case 1: Successful account lock
     * <p>
     * Given: Account exists with Auth0 integration
     * When: Admin locks the account
     * Then: Auth0 user is blocked (user.blocked = true)
     * And: Auth0 Management API is called with blocked = true
     * </p>
     */
    @Test
    @DisplayName("TC01: Lock account - Auth0 user is blocked")
    void lockAccount_validAccount_blocksAuth0User() throws Exception {
        // Arrange
        Account account = accountRepository.findById(TEST_ACCOUNT_ID).orElseThrow();
        assertThat(account.getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Act
        accountService.lockAccount(TEST_ACCOUNT_ID, ADMIN_ACCOUNT_ID);

        // Assert - verify Auth0ManagementApiClient.blockUser was called with correct Auth0 user ID
        verify(auth0Client, times(1)).blockUser(argThat(userId ->
            userId != null && userId.value().equals(MOCK_AUTH0_USER_ID)
        ));
    }

    /**
     * Test Case 2: Successful account unlock
     * <p>
     * Given: Account exists with Auth0 integration (previously blocked)
     * When: Admin unlocks the account
     * Then: Auth0 user is unblocked (user.blocked = false)
     * And: Auth0 Management API is called with blocked = false
     * </p>
     */
    @Test
    @DisplayName("TC02: Unlock account - Auth0 user is unblocked")
    void unlockAccount_validAccount_unblocksAuth0User() throws Exception {
        // Arrange
        Account account = accountRepository.findById(TEST_ACCOUNT_ID).orElseThrow();
        assertThat(account.getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Act
        accountService.unlockAccount(TEST_ACCOUNT_ID);

        // Assert - verify Auth0ManagementApiClient.unblockUser was called with correct Auth0 user ID
        verify(auth0Client, times(1)).unblockUser(argThat(userId ->
            userId != null && userId.value().equals(MOCK_AUTH0_USER_ID)
        ));
    }

    /**
     * Test Case 3: Admin cannot lock own account
     * <p>
     * Given: Admin account exists
     * When: Admin attempts to lock their own account
     * Then: CannotLockOwnAccountException is thrown
     * And: Auth0 Management API is NOT called
     * </p>
     */
    @Test
    @DisplayName("TC03: Lock own account - throws CannotLockOwnAccountException")
    void lockAccount_adminLocksOwnAccount_throwsCannotLockOwnAccountException() throws Exception {
        // Act & Assert
        assertThatThrownBy(() -> accountService.lockAccount(ADMIN_ACCOUNT_ID, ADMIN_ACCOUNT_ID))
            .isInstanceOf(CannotLockOwnAccountException.class)
            .hasMessageContaining("cannot lock your own account");

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).blockUser(any(Auth0UserId.class));
    }

    /**
     * Test Case 4: Lock account without Auth0 integration
     * <p>
     * Given: Account exists without Auth0 integration (identityProviderUserId is null)
     * When: Admin attempts to lock the account
     * Then: IllegalStateException is thrown
     * And: Auth0 Management API is NOT called
     * </p>
     */
    @Test
    @DisplayName("TC04: Lock account without Auth0 integration - throws IllegalStateException")
    void lockAccount_accountWithoutAuth0_throwsIllegalStateException() throws Exception {
        // Arrange - Create account without Auth0 integration
        Account accountWithoutAuth0 = Account.create("no-auth0@example.com", "No Auth0 User", null, null);
        Account savedAccount = accountRepository.save(accountWithoutAuth0);

        // Act & Assert
        assertThatThrownBy(() -> accountService.lockAccount(savedAccount.getId(), ADMIN_ACCOUNT_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Account does not have Auth0 integration");

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).blockUser(any(Auth0UserId.class));
    }

    /**
     * Test Case 5: Unlock account without Auth0 integration
     * <p>
     * Given: Account exists without Auth0 integration (identityProviderUserId is null)
     * When: Admin attempts to unlock the account
     * Then: IllegalStateException is thrown
     * And: Auth0 Management API is NOT called
     * </p>
     */
    @Test
    @DisplayName("TC05: Unlock account without Auth0 integration - throws IllegalStateException")
    void unlockAccount_accountWithoutAuth0_throwsIllegalStateException() throws Exception {
        // Arrange - Create account without Auth0 integration
        Account accountWithoutAuth0 = Account.create("no-auth0-unlock@example.com", "No Auth0 User Unlock", null, null);
        Account savedAccount = accountRepository.save(accountWithoutAuth0);

        // Act & Assert
        assertThatThrownBy(() -> accountService.unlockAccount(savedAccount.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Account does not have Auth0 integration");

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).unblockUser(any(Auth0UserId.class));
    }
}
