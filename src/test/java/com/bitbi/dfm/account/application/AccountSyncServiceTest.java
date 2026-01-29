package com.bitbi.dfm.account.application;

import com.auth0.exception.Auth0Exception;
import com.auth0.exception.APIException;
import com.auth0.json.mgmt.users.User;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountAuth0LinkedEvent;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.domain.UserRole;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.shared.exception.Auth0RateLimitException;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AccountSyncService.
 * <p>
 * Tests the two-phase commit pattern for creating accounts with Auth0 integration.
 * </p>
 *
 * Test Coverage:
 * - TC01: Successful account creation with Auth0
 * - TC02: Rollback Auth0 user when PostgreSQL save fails
 * - TC03: Account already exists (409 Conflict)
 * - TC04: Auth0 rate limit exceeded (503)
 * - TC05: Auth0 service unavailable (503)
 * - TC06: Domain event published on success
 * - TC07: Password generation meets requirements
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("AccountSyncService Unit Tests")
class AccountSyncServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Auth0ManagementApiClient auth0ManagementApiClient;

    @Captor
    private ArgumentCaptor<Account> accountCaptor;

    @Captor
    private ArgumentCaptor<AccountAuth0LinkedEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Map<String, Object>> metadataCaptor;

    private AccountSyncService service;

    @BeforeEach
    void setUp() {
        service = new AccountSyncService(accountRepository, eventPublisher, auth0ManagementApiClient);
    }

    @Test
    @DisplayName("TC01: Should create account with Auth0 integration successfully")
    void shouldCreateAccountWithAuth0Successfully() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";
        String name = "John Doe";
        String phone = "+12025551234";
        String company = "Acme Corp";

        // Mock Auth0 user creation via Auth0ManagementApiClient
        User auth0User = new User();
        auth0User.setId("auth0|123456");
        auth0User.setEmail(email);

        when(auth0ManagementApiClient.createUserWithPassword(eq(email), eq(name), anyString(), eq(true)))
                .thenReturn(auth0User);

        // Mock PostgreSQL account creation
        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
            return account;
        });

        // When
        var result = service.createAccount(email, name, phone, company, UserRole.USER);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getEmail()).isEqualTo(email);
        assertThat(result.account().getName()).isEqualTo(name);
        assertThat(result.account().getPhone()).isNotNull();
        assertThat(result.account().getPhone().getValue()).isEqualTo(phone);
        assertThat(result.account().getCompany()).isNotNull();
        assertThat(result.account().getCompany().getValue()).isEqualTo(company);
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo("auth0|123456");
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword()).hasSize(16);

        // Verify Auth0 user created via client
        verify(auth0ManagementApiClient).createUserWithPassword(eq(email), eq(name), anyString(), eq(true));

        // Verify PostgreSQL account created with Auth0 user ID
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getIdentityProviderUserId()).isEqualTo("auth0|123456");

        // Verify Auth0 metadata updated with accountId
        verify(auth0ManagementApiClient).updateUserMetadata(eq(Auth0UserId.of("auth0|123456")), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).containsKey("accountId");

        // Verify domain event published
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(AccountAuth0LinkedEvent.class);
        assertThat(eventCaptor.getValue().auth0UserId()).isEqualTo("auth0|123456");
    }

    @Test
    @DisplayName("TC02: Should rollback Auth0 user when PostgreSQL save fails")
    void shouldRollbackAuth0UserWhenPostgreSQLFails() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";
        String name = "John Doe";

        // Mock Auth0 user creation
        User auth0User = new User();
        auth0User.setId("auth0|123456");

        when(auth0ManagementApiClient.createUserWithPassword(eq(email), eq(name), anyString(), eq(true)))
                .thenReturn(auth0User);

        // Mock PostgreSQL failure
        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, name, null, null, UserRole.USER))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection failed");

        // Verify Auth0 user was deleted (rollback via client)
        verify(auth0ManagementApiClient).deleteUser(Auth0UserId.of("auth0|123456"));

        // Verify no domain event published
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("TC03: Should throw exception when account already exists")
    void shouldThrowExceptionWhenAccountExists() throws Auth0Exception {
        // Given
        String email = "existing@example.com";
        Account existingAccount = Account.create(email, "Existing User", null, null);

        when(accountRepository.findByEmail(email)).thenReturn(Optional.of(existingAccount));

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, "John Doe", null, null, UserRole.USER))
                .isInstanceOf(AccountService.AccountAlreadyExistsException.class)
                .hasMessageContaining("Account with email " + email + " already exists");

        // Verify no Auth0 API calls made
        verify(auth0ManagementApiClient, never()).createUserWithPassword(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("TC04: Should throw Auth0RateLimitException when rate limit exceeded")
    void shouldThrowAuth0RateLimitException() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());

        // Mock Auth0 rate limit (429)
        APIException rateLimitException = new APIException("Rate limit exceeded", 429, null);
        when(auth0ManagementApiClient.createUserWithPassword(eq(email), anyString(), anyString(), eq(true)))
                .thenThrow(rateLimitException);

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, "John Doe", null, null, UserRole.USER))
                .isInstanceOf(Auth0RateLimitException.class)
                .hasMessageContaining("Auth0 Management API rate limit exceeded");
    }

    @Test
    @DisplayName("TC05: Should throw Auth0ServiceUnavailableException when Auth0 is down")
    void shouldThrowAuth0ServiceUnavailableException() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());

        // Mock Auth0 service unavailable (503)
        APIException serviceException = new APIException("Service unavailable", 503, null);
        when(auth0ManagementApiClient.createUserWithPassword(eq(email), anyString(), anyString(), eq(true)))
                .thenThrow(serviceException);

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, "John Doe", null, null, UserRole.USER))
                .isInstanceOf(Auth0ServiceUnavailableException.class)
                .hasMessageContaining("Auth0 Management API unavailable");
    }

    @Test
    @DisplayName("TC06: Should handle Auth0 metadata update failure gracefully")
    void shouldHandleMetadataUpdateFailureGracefully() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";

        User auth0User = new User();
        auth0User.setId("auth0|123456");

        when(auth0ManagementApiClient.createUserWithPassword(eq(email), anyString(), anyString(), eq(true)))
                .thenReturn(auth0User);

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
            return account;
        });

        // Mock metadata update failure (non-critical)
        doThrow(new APIException("Metadata update failed", 500, null))
                .when(auth0ManagementApiClient).updateUserMetadata(any(Auth0UserId.class), any());

        // When
        var result = service.createAccount(email, "John Doe", null, null, UserRole.USER);

        // Then - should succeed despite metadata update failure
        assertThat(result).isNotNull();
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo("auth0|123456");

        // Verify domain event still published
        verify(eventPublisher).publishEvent(any(AccountAuth0LinkedEvent.class));
    }
}
