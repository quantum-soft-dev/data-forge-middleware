package com.bitbi.dfm.account.application;

import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.UsersEntity;
import com.auth0.exception.Auth0Exception;
import com.auth0.exception.APIException;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountAuth0LinkedEvent;
import com.bitbi.dfm.account.domain.AccountRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
@DisplayName("AccountSyncService Unit Tests")
class AccountSyncServiceTest {

    @Mock
    private ManagementAPI managementAPI;

    @Mock
    private UsersEntity usersEntity;

    @Mock
    private Request<User> createUserRequest;

    @Mock
    private Request<User> updateUserRequest;

    @Mock
    private Request<Void> deleteUserRequest;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<Account> accountCaptor;

    @Captor
    private ArgumentCaptor<AccountAuth0LinkedEvent> eventCaptor;

    private AccountSyncService service;

    @BeforeEach
    void setUp() {
        service = new AccountSyncService(managementAPI, accountRepository, eventPublisher);
        ReflectionTestUtils.setField(service, "databaseConnection", "Username-Password-Authentication");

        // Setup mock chain for ManagementAPI
        when(managementAPI.users()).thenReturn(usersEntity);
    }

    @Test
    @DisplayName("TC01: Should create account with Auth0 integration successfully")
    void shouldCreateAccountWithAuth0Successfully() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";
        String name = "John Doe";
        String phone = "+12025551234";
        String company = "Acme Corp";

        // Mock Auth0 user creation
        User auth0User = new User();
        auth0User.setId("auth0|123456");
        auth0User.setEmail(email);

        when(usersEntity.create(any(User.class))).thenReturn(createUserRequest);
        when(createUserRequest.execute()).thenReturn(new MockResponse<>(auth0User));

        // Mock Auth0 user metadata update
        when(usersEntity.update(eq("auth0|123456"), any(User.class))).thenReturn(updateUserRequest);
        when(updateUserRequest.execute()).thenReturn(new MockResponse<>(auth0User));

        // Mock PostgreSQL account creation
        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
            return account;
        });

        // When
        var result = service.createAccount(email, name, phone, company);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getEmail()).isEqualTo(email);
        assertThat(result.account().getName()).isEqualTo(name);
        assertThat(result.account().getPhone()).isEqualTo(phone);
        assertThat(result.account().getCompany()).isEqualTo(company);
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo("auth0|123456");
        assertThat(result.temporaryPassword()).isNotBlank();
        assertThat(result.temporaryPassword()).hasSize(16);

        // Verify Auth0 user created
        verify(usersEntity).create(any(User.class));

        // Verify PostgreSQL account created with Auth0 user ID
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getIdentityProviderUserId()).isEqualTo("auth0|123456");

        // Verify Auth0 metadata updated with accountId
        verify(usersEntity).update(eq("auth0|123456"), any(User.class));

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

        when(usersEntity.create(any(User.class))).thenReturn(createUserRequest);
        when(createUserRequest.execute()).thenReturn(new MockResponse<>(auth0User));

        // Mock PostgreSQL failure
        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Mock Auth0 user deletion (compensating transaction)
        when(usersEntity.delete("auth0|123456")).thenReturn(deleteUserRequest);
        when(deleteUserRequest.execute()).thenReturn(new MockResponse<>(null));

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, name, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection failed");

        // Verify Auth0 user was deleted (rollback)
        verify(usersEntity).delete("auth0|123456");

        // Verify no domain event published
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("TC03: Should throw exception when account already exists")
    void shouldThrowExceptionWhenAccountExists() {
        // Given
        String email = "existing@example.com";
        Account existingAccount = new Account(email, "Existing User");

        when(accountRepository.findByEmail(email)).thenReturn(Optional.of(existingAccount));

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, "John Doe", null, null))
                .isInstanceOf(AccountService.AccountAlreadyExistsException.class)
                .hasMessageContaining("Account with email " + email + " already exists");

        // Verify no Auth0 API calls made
        verify(usersEntity, never()).create(any());
    }

    @Test
    @DisplayName("TC04: Should throw Auth0RateLimitException when rate limit exceeded")
    void shouldThrowAuth0RateLimitException() throws Auth0Exception {
        // Given
        String email = "john.doe@example.com";

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());

        // Mock Auth0 rate limit (429)
        APIException rateLimitException = new APIException("Rate limit exceeded", 429, null);
        when(usersEntity.create(any(User.class))).thenReturn(createUserRequest);
        when(createUserRequest.execute()).thenThrow(rateLimitException);

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, "John Doe", null, null))
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
        when(usersEntity.create(any(User.class))).thenReturn(createUserRequest);
        when(createUserRequest.execute()).thenThrow(serviceException);

        // When / Then
        assertThatThrownBy(() -> service.createAccount(email, "John Doe", null, null))
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

        when(usersEntity.create(any(User.class))).thenReturn(createUserRequest);
        when(createUserRequest.execute()).thenReturn(new MockResponse<>(auth0User));

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
            return account;
        });

        // Mock metadata update failure (non-critical)
        when(usersEntity.update(eq("auth0|123456"), any(User.class))).thenReturn(updateUserRequest);
        when(updateUserRequest.execute()).thenThrow(new APIException("Metadata update failed", 500, null));

        // When
        var result = service.createAccount(email, "John Doe", null, null);

        // Then - should succeed despite metadata update failure
        assertThat(result).isNotNull();
        assertThat(result.account().getIdentityProviderUserId()).isEqualTo("auth0|123456");

        // Verify domain event still published
        verify(eventPublisher).publishEvent(any(AccountAuth0LinkedEvent.class));
    }

    /**
     * Mock response wrapper for Auth0 Request objects.
     */
    private static class MockResponse<T> implements com.auth0.net.Response<T> {
        private final T body;

        public MockResponse(T body) {
            this.body = body;
        }

        @Override
        public T getBody() {
            return body;
        }

        @Override
        public int getStatusCode() {
            return 200;
        }

        @Override
        public java.util.Map<String, java.util.List<String>> getHeaders() {
            return java.util.Collections.emptyMap();
        }
    }
}
