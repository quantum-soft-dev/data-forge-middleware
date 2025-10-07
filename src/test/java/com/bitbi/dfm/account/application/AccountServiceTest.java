package com.bitbi.dfm.account.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.shared.domain.events.AccountDeactivatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AccountService.
 */
@DisplayName("AccountService Unit Tests")
class AccountServiceTest {

    private AccountService accountService;
    private AccountRepository accountRepository;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        accountService = new AccountService(accountRepository, eventPublisher);
    }

    @Test
    @DisplayName("Should create account successfully")
    void shouldCreateAccountSuccessfully() {
        // Given
        String email = "test@example.com";
        String name = "Test Account";
        Account account = Account.create(email, name);

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        // When
        Account result = accountService.createAccount(email, name);

        // Then
        assertNotNull(result);
        assertEquals(email, result.getEmail());
        assertEquals(name, result.getName());
        verify(accountRepository).findByEmail(email);
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("Should throw exception when creating account with existing email")
    void shouldThrowExceptionWhenCreatingAccountWithExistingEmail() {
        // Given
        String email = "existing@example.com";
        String name = "Test Account";
        Account existingAccount = Account.create(email, "Existing");

        when(accountRepository.findByEmail(email)).thenReturn(Optional.of(existingAccount));

        // When & Then
        AccountService.AccountAlreadyExistsException exception =
                assertThrows(AccountService.AccountAlreadyExistsException.class, () ->
                        accountService.createAccount(email, name)
                );
        assertTrue(exception.getMessage().contains(email));
        verify(accountRepository).findByEmail(email);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should get account by ID successfully")
    void shouldGetAccountByIdSuccessfully() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account account = Account.create("test@example.com", "Test");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // When
        Account result = accountService.getAccount(accountId);

        // Then
        assertNotNull(result);
        assertEquals(account, result);
        verify(accountRepository).findById(accountId);
    }

    @Test
    @DisplayName("Should throw exception when account not found by ID")
    void shouldThrowExceptionWhenAccountNotFoundById() {
        // Given
        UUID accountId = UUID.randomUUID();

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // When & Then
        AccountService.AccountNotFoundException exception =
                assertThrows(AccountService.AccountNotFoundException.class, () ->
                        accountService.getAccount(accountId)
                );
        assertTrue(exception.getMessage().contains(accountId.toString()));
        verify(accountRepository).findById(accountId);
    }

    @Test
    @DisplayName("Should get account by email successfully")
    void shouldGetAccountByEmailSuccessfully() {
        // Given
        String email = "test@example.com";
        Account account = Account.create(email, "Test");

        when(accountRepository.findByEmail(email)).thenReturn(Optional.of(account));

        // When
        Account result = accountService.getAccountByEmail(email);

        // Then
        assertNotNull(result);
        assertEquals(account, result);
        verify(accountRepository).findByEmail(email);
    }

    @Test
    @DisplayName("Should throw exception when account not found by email")
    void shouldThrowExceptionWhenAccountNotFoundByEmail() {
        // Given
        String email = "notfound@example.com";

        when(accountRepository.findByEmail(email)).thenReturn(Optional.empty());

        // When & Then
        AccountService.AccountNotFoundException exception =
                assertThrows(AccountService.AccountNotFoundException.class, () ->
                        accountService.getAccountByEmail(email)
                );
        assertTrue(exception.getMessage().contains(email));
        verify(accountRepository).findByEmail(email);
    }

    @Test
    @DisplayName("Should list active accounts")
    void shouldListActiveAccounts() {
        // Given
        Account account1 = Account.create("test1@example.com", "Account 1");
        Account account2 = Account.create("test2@example.com", "Account 2");
        List<Account> accounts = Arrays.asList(account1, account2);

        when(accountRepository.findAllActive()).thenReturn(accounts);

        // When
        List<Account> result = accountService.listActiveAccounts();

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        verify(accountRepository).findAllActive();
    }

    @Test
    @DisplayName("Should list accounts with pagination")
    void shouldListAccountsWithPagination() {
        // Given
        Account account1 = Account.create("test1@example.com", "Account 1");
        Account account2 = Account.create("test2@example.com", "Account 2");
        List<Account> accounts = Arrays.asList(account1, account2);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Account> page = new PageImpl<>(accounts, pageable, 2);

        when(accountRepository.findAll(pageable)).thenReturn(page);

        // When
        Page<Account> result = accountService.listAccounts(pageable);

        // Then
        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertEquals(2, result.getTotalElements());
        verify(accountRepository).findAll(pageable);
    }

    @Test
    @DisplayName("Should update account successfully")
    void shouldUpdateAccountSuccessfully() {
        // Given
        UUID accountId = UUID.randomUUID();
        String newName = "Updated Name";
        Account account = Account.create("test@example.com", "Old Name");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        // When
        Account result = accountService.updateAccount(accountId, newName);

        // Then
        assertNotNull(result);
        verify(accountRepository).findById(accountId);
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("Should throw exception when updating non-existent account")
    void shouldThrowExceptionWhenUpdatingNonExistentAccount() {
        // Given
        UUID accountId = UUID.randomUUID();
        String newName = "Updated Name";

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(AccountService.AccountNotFoundException.class, () ->
                accountService.updateAccount(accountId, newName)
        );
        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should deactivate account successfully")
    void shouldDeactivateAccountSuccessfully() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account account = Account.create("test@example.com", "Test");

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        // When
        accountService.deactivateAccount(accountId);

        // Then
        verify(accountRepository).findById(accountId);
        verify(accountRepository).save(account);
        verify(eventPublisher).publishEvent(any(AccountDeactivatedEvent.class));
    }

    @Test
    @DisplayName("Should not publish event when deactivating already inactive account")
    void shouldNotPublishEventWhenDeactivatingAlreadyInactiveAccount() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account account = Account.create("test@example.com", "Test");
        account.deactivate(); // Already deactivated

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // When
        accountService.deactivateAccount(accountId);

        // Then
        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Should throw exception when deactivating non-existent account")
    void shouldThrowExceptionWhenDeactivatingNonExistentAccount() {
        // Given
        UUID accountId = UUID.randomUUID();

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(AccountService.AccountNotFoundException.class, () ->
                accountService.deactivateAccount(accountId)
        );
        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Should reactivate account successfully")
    void shouldReactivateAccountSuccessfully() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account account = Account.create("test@example.com", "Test");
        account.deactivate(); // Deactivate first

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        // When
        Account result = accountService.reactivateAccount(accountId);

        // Then
        assertNotNull(result);
        verify(accountRepository).findById(accountId);
        verify(accountRepository).save(account);
    }

    @Test
    @DisplayName("Should return account when reactivating already active account")
    void shouldReturnAccountWhenReactivatingAlreadyActiveAccount() {
        // Given
        UUID accountId = UUID.randomUUID();
        Account account = Account.create("test@example.com", "Test");
        // Account is already active

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        // When
        Account result = accountService.reactivateAccount(accountId);

        // Then
        assertNotNull(result);
        assertEquals(account, result);
        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw exception when reactivating non-existent account")
    void shouldThrowExceptionWhenReactivatingNonExistentAccount() {
        // Given
        UUID accountId = UUID.randomUUID();

        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(AccountService.AccountNotFoundException.class, () ->
                accountService.reactivateAccount(accountId)
        );
        verify(accountRepository).findById(accountId);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("AccountAlreadyExistsException should have message")
    void accountAlreadyExistsExceptionShouldHaveMessage() {
        // Given
        String message = "Test message";

        // When
        AccountService.AccountAlreadyExistsException exception =
                new AccountService.AccountAlreadyExistsException(message);

        // Then
        assertEquals(message, exception.getMessage());
    }

    @Test
    @DisplayName("AccountNotFoundException should have message")
    void accountNotFoundExceptionShouldHaveMessage() {
        // Given
        String message = "Test message";

        // When
        AccountService.AccountNotFoundException exception =
                new AccountService.AccountNotFoundException(message);

        // Then
        assertEquals(message, exception.getMessage());
    }
}
