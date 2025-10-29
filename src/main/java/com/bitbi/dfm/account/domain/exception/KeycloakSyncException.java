package com.bitbi.dfm.account.domain.exception;

import java.util.UUID;

/**
 * Exception thrown when synchronization with Keycloak fails.
 * <p>
 * This is a domain exception indicating that an operation to sync account data
 * with Keycloak has failed due to communication errors, authentication issues,
 * or other Keycloak-related problems.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class KeycloakSyncException extends RuntimeException {

    private final UUID accountId;
    private final String operation;

    /**
     * Constructs a new KeycloakSyncException with the specified account ID and operation.
     *
     * @param accountId the ID of the account being synced
     * @param operation the operation that failed (e.g., "lock", "unlock", "resetPassword")
     * @param message   the detailed error message
     */
    public KeycloakSyncException(UUID accountId, String operation, String message) {
        super(String.format("Keycloak sync failed for account %s during operation '%s': %s",
                accountId, operation, message));
        this.accountId = accountId;
        this.operation = operation;
    }

    /**
     * Constructs a new KeycloakSyncException with the specified account ID, operation, and cause.
     *
     * @param accountId the ID of the account being synced
     * @param operation the operation that failed
     * @param message   the detailed error message
     * @param cause     the underlying cause of the exception
     */
    public KeycloakSyncException(UUID accountId, String operation, String message, Throwable cause) {
        super(String.format("Keycloak sync failed for account %s during operation '%s': %s",
                accountId, operation, message), cause);
        this.accountId = accountId;
        this.operation = operation;
    }

    /**
     * Constructs a new KeycloakSyncException with a simple message and cause.
     *
     * @param message the detailed error message
     * @param cause   the underlying cause of the exception
     */
    public KeycloakSyncException(String message, Throwable cause) {
        super(message, cause);
        this.accountId = null;
        this.operation = null;
    }

    /**
     * Returns the ID of the account that failed to sync.
     *
     * @return the account ID, or null if not applicable
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Returns the operation that was being performed when the sync failed.
     *
     * @return the operation name, or null if not applicable
     */
    public String getOperation() {
        return operation;
    }
}
