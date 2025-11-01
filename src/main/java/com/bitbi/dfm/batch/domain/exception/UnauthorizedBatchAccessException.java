package com.bitbi.dfm.batch.domain.exception;

import java.util.UUID;

/**
 * Exception thrown when a user attempts to access a batch they don't own.
 * <p>
 * This is a security exception indicating unauthorized access attempt.
 * </p>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 */
public class UnauthorizedBatchAccessException extends RuntimeException {

    private final UUID batchId;
    private final UUID accountId;

    /**
     * Constructs a new UnauthorizedBatchAccessException.
     *
     * @param batchId   the ID of the batch being accessed
     * @param accountId the ID of the account attempting access
     */
    public UnauthorizedBatchAccessException(UUID batchId, UUID accountId) {
        super(String.format("Account %s is not authorized to access batch %s", accountId, batchId));
        this.batchId = batchId;
        this.accountId = accountId;
    }

    /**
     * Constructs a new UnauthorizedBatchAccessException with custom message.
     *
     * @param batchId   the ID of the batch being accessed
     * @param accountId the ID of the account attempting access
     * @param message   custom error message
     */
    public UnauthorizedBatchAccessException(UUID batchId, UUID accountId, String message) {
        super(message);
        this.batchId = batchId;
        this.accountId = accountId;
    }

    /**
     * Returns the ID of the batch being accessed.
     *
     * @return the batch ID
     */
    public UUID getBatchId() {
        return batchId;
    }

    /**
     * Returns the ID of the account attempting access.
     *
     * @return the account ID
     */
    public UUID getAccountId() {
        return accountId;
    }
}
