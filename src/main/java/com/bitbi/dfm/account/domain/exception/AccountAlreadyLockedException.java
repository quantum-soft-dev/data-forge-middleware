package com.bitbi.dfm.account.domain.exception;

import java.util.UUID;

/**
 * Exception thrown when attempting to lock an account that is already locked.
 * <p>
 * This is a domain exception indicating that the account is already in a locked state
 * and cannot be locked again.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class AccountAlreadyLockedException extends RuntimeException {

    private final UUID accountId;

    /**
     * Constructs a new AccountAlreadyLockedException with the specified account ID.
     *
     * @param accountId the ID of the account that is already locked
     */
    public AccountAlreadyLockedException(UUID accountId) {
        super(String.format("Account is already locked: %s", accountId));
        this.accountId = accountId;
    }

    /**
     * Constructs a new AccountAlreadyLockedException with the specified account ID and cause.
     *
     * @param accountId the ID of the account that is already locked
     * @param cause     the cause of the exception
     */
    public AccountAlreadyLockedException(UUID accountId, Throwable cause) {
        super(String.format("Account is already locked: %s", accountId), cause);
        this.accountId = accountId;
    }

    /**
     * Returns the ID of the account that is already locked.
     *
     * @return the account ID
     */
    public UUID getAccountId() {
        return accountId;
    }
}
