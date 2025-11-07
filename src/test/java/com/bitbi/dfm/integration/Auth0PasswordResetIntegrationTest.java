package com.bitbi.dfm.integration;

import com.bitbi.dfm.account.application.AccountService;
import com.bitbi.dfm.account.application.AccountSyncService;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.account.presentation.dto.ResetPasswordResponseDto;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.config.TestSecurityConfig;
import com.bitbi.dfm.shared.exception.MissingAuth0IntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test for Auth0 password reset operations.
 * <p>
 * Tests the Auth0 password change ticket generation:
 * 1. Generate password reset link for valid account
 * 2. Verify ticket URL format
 * 3. Verify ticket expiry (24 hours default)
 * 4. Handle account without Auth0 integration
 * </p>
 *
 * User Story: US3 - Admin Resets User Password
 * Task: T046 - Integration test for password reset
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@SpringBootTest(properties = {
    "auth0.database-connection=Username-Password-Authentication",
    "auth0.domain=test.auth0.com",
    "auth0.api.audience=https://test-api.example.com",
    "auth0.password-reset-ttl-seconds=86400"
})
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql("/test-data.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Auth0 Password Reset Integration Test - User Story 3")
class Auth0PasswordResetIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private Auth0ManagementApiClient auth0Client;

    @MockitoBean
    private AccountSyncService accountSyncService;

    private static final String MOCK_AUTH0_USER_ID = "auth0|60f7b8a8b4a0f10074c5d0e1";
    private static final String MOCK_RESET_LINK = "https://test.auth0.com/lo/reset?ticket=mockTicketToken123";
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("0199bab1-fad2-bf76-c478-eae1f61e1c17"); // Test Account 2 from test-data.sql (has Auth0 integration)
    private static final UUID ACCOUNT_WITHOUT_AUTH0_ID = UUID.fromString("0199bab2-3cbd-cc95-a989-57ba51d258c8"); // Inactive Account from test-data.sql (no Auth0 integration)

    @BeforeEach
    void setUp() throws Exception {
        // Mock Auth0ManagementApiClient.generatePasswordResetLink to return a mock reset link
        when(auth0Client.generatePasswordResetLink(any(Auth0UserId.class), nullable(String.class)))
            .thenReturn(MOCK_RESET_LINK);
    }

    /**
     * Test Case 1: Successful password reset link generation
     * <p>
     * Given: Account exists with Auth0 integration
     * When: Admin requests password reset
     * Then: Auth0 password change ticket is created
     * And: Response contains password reset link
     * And: Link expires in 24 hours
     * </p>
     */
    @Test
    @DisplayName("TC01: Reset password - Auth0 ticket URL generated")
    void resetPassword_validAccount_generatesResetLink() throws Exception {
        // Arrange
        Account account = accountRepository.findById(TEST_ACCOUNT_ID).orElseThrow();
        assertThat(account.getIdentityProviderUserId()).isEqualTo(MOCK_AUTH0_USER_ID);

        // Act
        ResetPasswordResponseDto response = accountService.resetPassword(TEST_ACCOUNT_ID);

        // Assert - verify response contains reset link and metadata
        assertThat(response.passwordResetLink()).isEqualTo(MOCK_RESET_LINK);
        assertThat(response.accountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(response.email()).isEqualTo(account.getEmail());
        assertThat(response.expiresAt()).isNotNull();

        // Verify expiry is 24 hours from now (with 1-minute tolerance)
        Instant expectedExpiry = Instant.now().plus(24, ChronoUnit.HOURS);
        assertThat(response.expiresAt()).isBetween(
            expectedExpiry.minus(1, ChronoUnit.MINUTES),
            expectedExpiry.plus(1, ChronoUnit.MINUTES)
        );

        // Verify Auth0ManagementApiClient.generatePasswordResetLink was called with correct Auth0 user ID
        verify(auth0Client, times(1)).generatePasswordResetLink(
            argThat(userId -> userId != null && userId.value().equals(MOCK_AUTH0_USER_ID)),
            nullable(String.class)
        );
    }

    /**
     * Test Case 2: Password reset link format validation
     * <p>
     * Given: Account exists with Auth0 integration
     * When: Admin requests password reset
     * Then: Returned link matches Auth0 ticket URL format
     * And: Link contains https://{domain}/lo/reset?ticket={token}
     * </p>
     */
    @Test
    @DisplayName("TC02: Reset password - ticket URL format is correct")
    void resetPassword_validAccount_ticketUrlFormatIsValid() throws Exception {
        // Act
        ResetPasswordResponseDto response = accountService.resetPassword(TEST_ACCOUNT_ID);

        // Assert - verify URL format
        assertThat(response.passwordResetLink())
            .startsWith("https://")
            .contains("/lo/reset?ticket=");
    }

    /**
     * Test Case 3: Password reset for account without Auth0 integration
     * <p>
     * Given: Account exists but has no Auth0 user ID (identity_provider_user_id is NULL)
     * When: Admin requests password reset
     * Then: MissingAuth0IntegrationException is thrown
     * And: Auth0 Management API is NOT called
     * </p>
     */
    @Test
    @DisplayName("TC03: Reset password - account without Auth0 integration throws exception")
    void resetPassword_noAuth0Integration_throwsMissingAuth0IntegrationException() throws Exception {
        // Arrange
        Account account = accountRepository.findById(ACCOUNT_WITHOUT_AUTH0_ID).orElseThrow();
        assertThat(account.getIdentityProviderUserId()).isNull();

        // Act & Assert
        assertThatThrownBy(() -> accountService.resetPassword(ACCOUNT_WITHOUT_AUTH0_ID))
            .isInstanceOf(MissingAuth0IntegrationException.class)
            .hasMessageContaining("Auth0 integration");

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).generatePasswordResetLink(any(Auth0UserId.class), nullable(String.class));
    }

    /**
     * Test Case 4: Password reset for non-existent account
     * <p>
     * Given: Account does not exist
     * When: Admin requests password reset
     * Then: AccountNotFoundException is thrown
     * And: Auth0 Management API is NOT called
     * </p>
     */
    @Test
    @DisplayName("TC04: Reset password - non-existent account throws AccountNotFoundException")
    void resetPassword_nonExistentAccount_throwsAccountNotFoundException() throws Exception {
        // Arrange
        UUID nonExistentAccountId = UUID.fromString("00000000-0000-0000-0000-000000000000");

        // Act & Assert
        assertThatThrownBy(() -> accountService.resetPassword(nonExistentAccountId))
            .isInstanceOf(AccountService.AccountNotFoundException.class)
            .hasMessageContaining("Account not found");

        // Verify Auth0 Management API was NOT called
        verify(auth0Client, never()).generatePasswordResetLink(any(Auth0UserId.class), nullable(String.class));
    }
}
