package com.bitbi.dfm.account.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.shared.domain.events.AccountDeactivatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AccountService(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
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
