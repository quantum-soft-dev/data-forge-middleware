package com.bitbi.dfm.shared.exception;

import java.util.UUID;

/**
 * Exception thrown when an operation requires Auth0 integration but the account has no Auth0 user ID.
 * <p>
 * This exception is thrown when attempting Auth0-specific operations (password reset, lock/unlock)
 * on accounts that were not created through the Auth0 integration flow or have not been linked to Auth0.
 * </p>
 *
 * User Story: US3 - Admin Resets User Password
 * Functional Requirement: FR-011 - Validate Auth0 integration before password reset
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class MissingAuth0IntegrationException extends RuntimeException {

    /**
     * Constructs a new MissingAuth0IntegrationException with a default message.
     *
     * @param accountId the account ID that is missing Auth0 integration
     */
    public MissingAuth0IntegrationException(UUID accountId) {
        super(String.format("Account %s does not have Auth0 integration. " +
            "This account cannot use Auth0-specific operations (password reset, lock/unlock). " +
            "The account must be created through the Auth0 integration flow or manually linked.", accountId));
    }

    /**
     * Constructs a new MissingAuth0IntegrationException with a custom message.
     *
     * @param message the detailed error message
     */
    public MissingAuth0IntegrationException(String message) {
        super(message);
    }

    /**
     * Constructs a new MissingAuth0IntegrationException with a custom message and cause.
     *
     * @param message the detailed error message
     * @param cause   the cause of the exception
     */
    public MissingAuth0IntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
