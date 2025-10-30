package com.bitbi.dfm.account.domain.exception;

import java.util.UUID;

/**
 * Exception thrown when an account cannot be found.
 * <p>
 * This is a domain exception indicating that the requested account
 * does not exist in the system.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class AccountNotFoundException extends RuntimeException {

    private final UUID accountId;

    /**
     * Constructs a new AccountNotFoundException with the specified account ID.
     *
     * @param accountId the ID of the account that was not found
     */
    public AccountNotFoundException(UUID accountId) {
        super(String.format("Account not found with ID: %s", accountId));
        this.accountId = accountId;
    }

    /**
     * Constructs a new AccountNotFoundException with the specified account ID and cause.
     *
     * @param accountId the ID of the account that was not found
     * @param cause     the cause of the exception
     */
    public AccountNotFoundException(UUID accountId, Throwable cause) {
        super(String.format("Account not found with ID: %s", accountId), cause);
        this.accountId = accountId;
    }

    /**
     * Returns the ID of the account that was not found.
     *
     * @return the account ID
     */
    public UUID getAccountId() {
        return accountId;
    }
}
