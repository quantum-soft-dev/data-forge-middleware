package com.bitbi.dfm.account.application;

import com.auth0.exception.Auth0Exception;
import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.account.presentation.dto.ResetPasswordResponseDto;
import com.bitbi.dfm.auth.domain.Auth0UserId;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.shared.domain.events.AccountDeactivatedEvent;
import com.bitbi.dfm.shared.exception.Auth0RateLimitException;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import com.bitbi.dfm.shared.exception.CannotLockOwnAccountException;
import com.bitbi.dfm.shared.exception.MissingAuth0IntegrationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application service for account management operations.
 * <p>
 * Handles account CRUD operations and publishes domain events.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class AccountService {

    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Auth0ManagementApiClient auth0Client;
    private final Counter passwordResetTicketsCounter;

    public AccountService(
            AccountRepository accountRepository,
            ApplicationEventPublisher eventPublisher,
            Auth0ManagementApiClient auth0Client,
            MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
        this.auth0Client = auth0Client;

        // Initialize Micrometer counter for password reset tickets generated
        this.passwordResetTicketsCounter = Counter.builder("auth0.password_reset.tickets.generated")
                .description("Total number of Auth0 password reset tickets generated")
                .tag("service", "account")
                .register(meterRegistry);
    }

    /**
     * Create new account.
     *
     * @param email   account email (must be unique)
     * @param name    account name
     * @param phone   account phone number (optional)
     * @param company account company name (optional)
     * @return created account
     * @throws AccountAlreadyExistsException if email already exists
     */
    public Account createAccount(String email, String name, String phone, String company) {
        logger.info("Creating new account: email={}, name={}, phone={}, company={}",
                   email, name, phone != null ? "[set]" : "[not set]", company != null ? "[set]" : "[not set]");

        if (accountRepository.findByEmail(email).isPresent()) {
            throw new AccountAlreadyExistsException("Account with email already exists: " + email);
        }

        // Wrap validation exceptions with field context for better error messages
        Account account;
        try {
            account = Account.create(email, name, phone, company);
        } catch (IllegalArgumentException e) {
            // Determine which field caused the error by analyzing the exception message
            String message = e.getMessage();
            if (message.contains("phone") || message.contains("E.164")) {
                throw new IllegalArgumentException("Invalid phone number: " + message, e);
            } else if (message.contains("Company") || message.contains("company")) {
                throw new IllegalArgumentException("Invalid company name: " + message, e);
            } else if (message.contains("email") || message.contains("Email")) {
                throw new IllegalArgumentException("Invalid email: " + message, e);
            } else if (message.contains("name") || message.contains("Name")) {
                throw new IllegalArgumentException("Invalid name: " + message, e);
            }
            // Re-throw if we can't determine the field
            throw e;
        }

        Account saved = accountRepository.save(account);

        logger.info("Account created successfully: id={}, email={}", saved.getId(), saved.getEmail());
        return saved;
    }

    /**
     * Get account by ID.
     *
     * @param accountId account identifier
     * @return account
     * @throws AccountNotFoundException if account not found
     */
    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
    }

    /**
     * Get account by email.
     *
     * @param email account email
     * @return account
     * @throws AccountNotFoundException if account not found
     */
    @Transactional(readOnly = true)
    public Account getAccountByEmail(String email) {
        return accountRepository.findByEmail(email)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + email));
    }

    /**
     * List all active accounts.
     *
     * @return list of active accounts
     */
    @Transactional(readOnly = true)
    public List<Account> listActiveAccounts() {
        return accountRepository.findAllActive();
    }

    /**
     * List all accounts with pagination.
     *
     * @param pageable pagination parameters
     * @return paginated list of accounts
     */
    @Transactional(readOnly = true)
    public Page<Account> listAccounts(Pageable pageable) {
        return accountRepository.findAll(pageable);
    }

    /**
     * Update account information.
     * Only updates fields that are non-null (partial update).
     *
     * @param accountId account identifier
     * @param name      new account name (optional)
     * @param phone     new account phone (optional)
     * @param company   new account company (optional)
     * @return updated account
     * @throws AccountNotFoundException if account not found
     */
    public Account updateAccount(UUID accountId, String name, String phone, String company) {
        logger.info("Updating account: id={}, name={}, phone={}, company={}",
                   accountId,
                   name != null ? "[updating]" : "[unchanged]",
                   phone != null ? "[updating]" : "[unchanged]",
                   company != null ? "[updating]" : "[unchanged]");

        Account account = getAccount(accountId);

        // Partial update - only update fields that are provided
        // Wrap each update with field-specific error context
        if (name != null && !name.isBlank()) {
            try {
                account.updateName(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid name: " + e.getMessage(), e);
            }
        }
        if (phone != null) {
            try {
                account.updatePhone(phone);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid phone number: " + e.getMessage(), e);
            }
        }
        if (company != null) {
            try {
                account.updateCompany(company);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid company name: " + e.getMessage(), e);
            }
        }

        Account saved = accountRepository.save(account);

        logger.info("Account updated successfully: id={}", accountId);
        return saved;
    }

    /**
     * Deactivate account (soft delete).
     * <p>
     * Publishes AccountDeactivatedEvent for cascade deactivation of sites.
     * </p>
     *
     * @param accountId account identifier
     * @throws AccountNotFoundException if account not found
     */
    public void deactivateAccount(UUID accountId) {
        logger.info("Deactivating account: id={}", accountId);

        Account account = getAccount(accountId);

        if (!account.getIsActive()) {
            logger.warn("Account already deactivated: id={}", accountId);
            return;
        }

        account.deactivate();
        accountRepository.save(account);

        // Publish domain event for cascade deactivation
        AccountDeactivatedEvent event = new AccountDeactivatedEvent(accountId);
        eventPublisher.publishEvent(event);

        logger.info("Account deactivated successfully: id={}", accountId);
    }

    /**
     * Reactivate previously deactivated account.
     *
     * @param accountId account identifier
     * @return reactivated account
     * @throws AccountNotFoundException if account not found
     */
    public Account reactivateAccount(UUID accountId) {
        logger.info("Reactivating account: id={}", accountId);

        Account account = getAccount(accountId);

        if (account.getIsActive()) {
            logger.warn("Account already active: id={}", accountId);
            return account;
        }

        account.activate();
        Account saved = accountRepository.save(account);

        logger.info("Account reactivated successfully: id={}", accountId);
        return saved;
    }

    /**
     * Lock account (block Auth0 user).
     * <p>
     * Prevents user from logging in via Auth0. Existing sessions remain valid until token expiry.
     * Admin cannot lock their own account (security measure).
     * </p>
     *
     * User Story: US2 - Admin Locks/Unlocks User Accounts
     * Task: T042 - Update AccountService with lockAccount method
     *
     * @param accountId      account identifier to lock
     * @param adminAccountId admin performing the lock operation
     * @throws AccountNotFoundException        if account not found
     * @throws CannotLockOwnAccountException  if admin attempts to lock their own account
     * @throws IllegalStateException           if account does not have Auth0 integration
     * @throws Auth0ServiceUnavailableException if Auth0 API is unavailable
     * @throws Auth0RateLimitException         if Auth0 rate limit exceeded
     */
    public void lockAccount(UUID accountId, UUID adminAccountId) {
        MDC.put("accountId", accountId.toString());
        MDC.put("adminAccountId", adminAccountId.toString());

        logger.info("Locking account: accountId={}, adminAccountId={}", accountId, adminAccountId);

        try {
            // Prevent self-lock
            if (accountId.equals(adminAccountId)) {
                throw new CannotLockOwnAccountException(adminAccountId);
            }

            Account account = getAccount(accountId);

            // Verify account has Auth0 integration
            if (account.getIdentityProviderUserId() == null || account.getIdentityProviderUserId().isBlank()) {
                throw new IllegalStateException("Account does not have Auth0 integration");
            }

            Auth0UserId auth0UserId = Auth0UserId.of(account.getIdentityProviderUserId());
            MDC.put("auth0UserId", auth0UserId.value());

            // Block user in Auth0
            try {
                auth0Client.blockUser(auth0UserId);
            } catch (Auth0Exception e) {
                handleAuth0Exception(e, "lock account");
            }

            logger.info("Account locked successfully: accountId={}, auth0UserId={}", accountId, auth0UserId);

        } finally {
            MDC.remove("accountId");
            MDC.remove("adminAccountId");
            MDC.remove("auth0UserId");
        }
    }

    /**
     * Unlock account (unblock Auth0 user).
     * <p>
     * Allows previously blocked user to log in again via Auth0.
     * </p>
     *
     * User Story: US2 - Admin Locks/Unlocks User Accounts
     * Task: T042 - Update AccountService with unlockAccount method
     *
     * @param accountId account identifier to unlock
     * @throws AccountNotFoundException        if account not found
     * @throws IllegalStateException           if account does not have Auth0 integration
     * @throws Auth0ServiceUnavailableException if Auth0 API is unavailable
     * @throws Auth0RateLimitException         if Auth0 rate limit exceeded
     */
    public void unlockAccount(UUID accountId) {
        MDC.put("accountId", accountId.toString());

        logger.info("Unlocking account: accountId={}", accountId);

        try {
            Account account = getAccount(accountId);

            // Verify account has Auth0 integration
            if (account.getIdentityProviderUserId() == null || account.getIdentityProviderUserId().isBlank()) {
                throw new IllegalStateException("Account does not have Auth0 integration");
            }

            Auth0UserId auth0UserId = Auth0UserId.of(account.getIdentityProviderUserId());
            MDC.put("auth0UserId", auth0UserId.value());

            // Unblock user in Auth0
            try {
                auth0Client.unblockUser(auth0UserId);
            } catch (Auth0Exception e) {
                handleAuth0Exception(e, "unlock account");
            }

            logger.info("Account unlocked successfully: accountId={}, auth0UserId={}", accountId, auth0UserId);

        } finally {
            MDC.remove("accountId");
            MDC.remove("auth0UserId");
        }
    }

    /**
     * Reset user password by generating Auth0 password reset link.
     * <p>
     * Generates a password change ticket (24-hour expiry) that directs the user to
     * Auth0 Universal Login to set a new password. The ticket URL is returned and
     * must be sent to the user via email or other communication channel.
     * </p>
     *
     * User Story: US3 - Admin Resets User Password
     * Task: T048 - Update AccountService with resetPassword method
     *
     * @param accountId account identifier to reset password for
     * @return ResetPasswordResponseDto containing password reset link and metadata
     * @throws AccountNotFoundException           if account not found
     * @throws MissingAuth0IntegrationException   if account does not have Auth0 integration
     * @throws Auth0ServiceUnavailableException   if Auth0 API is unavailable
     * @throws Auth0RateLimitException            if Auth0 rate limit exceeded
     */
    public ResetPasswordResponseDto resetPassword(UUID accountId) {
        MDC.put("accountId", accountId.toString());

        logger.info("Resetting password for account: accountId={}", accountId);

        try {
            Account account = getAccount(accountId);

            // Verify account has Auth0 integration
            if (account.getIdentityProviderUserId() == null || account.getIdentityProviderUserId().isBlank()) {
                throw new MissingAuth0IntegrationException(accountId);
            }

            Auth0UserId auth0UserId = Auth0UserId.of(account.getIdentityProviderUserId());
            MDC.put("auth0UserId", auth0UserId.value());

            // Generate password reset link via Auth0
            String passwordResetLink;
            try {
                passwordResetLink = auth0Client.generatePasswordResetLink(auth0UserId, null);
            } catch (Auth0Exception e) {
                handleAuth0Exception(e, "reset password");
                throw new RuntimeException("Unreachable - handleAuth0Exception should throw");
            }

            logger.info("Password reset link generated successfully: accountId={}, auth0UserId={}", accountId, auth0UserId);

            // Increment Micrometer counter for password reset tickets
            passwordResetTicketsCounter.increment();

            // Create response DTO with 24-hour expiry
            return ResetPasswordResponseDto.of(accountId, account.getEmail(), passwordResetLink);

        } finally {
            MDC.remove("accountId");
            MDC.remove("auth0UserId");
        }
    }

    /**
     * Handle Auth0 exceptions and convert to application exceptions.
     *
     * @param e         Auth0 exception
     * @param operation operation being performed
     */
    private void handleAuth0Exception(Auth0Exception e, String operation) {
        String message = e.getMessage();

        // Check if it's an APIException with status code
        if (e instanceof com.auth0.exception.APIException apiException) {
            int statusCode = apiException.getStatusCode();

            logger.error("Auth0 API error during {}: statusCode={}, message={}", operation, statusCode, message, e);

            if (statusCode == 429) {
                throw new Auth0RateLimitException("Auth0 rate limit exceeded during " + operation);
            } else if (statusCode >= 500) {
                throw new Auth0ServiceUnavailableException("Auth0 service unavailable during " + operation);
            } else {
                throw new RuntimeException("Auth0 API error during " + operation + ": " + message, e);
            }
        } else {
            logger.error("Auth0 error during {}: message={}", operation, message, e);
            throw new RuntimeException("Auth0 error during " + operation + ": " + message, e);
        }
    }

    /**
     * Exception thrown when account already exists.
     */
    public static class AccountAlreadyExistsException extends RuntimeException {
        public AccountAlreadyExistsException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when account is not found.
     */
    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String message) {
            super(message);
        }
    }
}
