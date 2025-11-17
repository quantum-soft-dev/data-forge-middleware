package com.bitbi.dfm.shared.exception;

import java.util.UUID;

/**
 * Exception thrown when an admin attempts to lock their own account.
 * <p>
 * This is a security measure to prevent admins from accidentally locking themselves out
 * of the system. Admins must have another admin lock their account if needed.
 * </p>
 *
 * User Story: US2 - Admin Locks/Unlocks User Accounts
 * Functional Requirement: FR-007 - Prevent self-lock
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class CannotLockOwnAccountException extends RuntimeException {

    /**
     * Constructs a new CannotLockOwnAccountException with a default message.
     *
     * @param adminAccountId the account ID of the admin attempting to lock their own account
     */
    public CannotLockOwnAccountException(UUID adminAccountId) {
        super(String.format("Admin with account ID %s cannot lock your own account. " +
            "Please ask another administrator to lock your account if needed.", adminAccountId));
    }

    /**
     * Constructs a new CannotLockOwnAccountException with a custom message.
     *
     * @param message the detailed error message
     */
    public CannotLockOwnAccountException(String message) {
        super(message);
    }

    /**
     * Constructs a new CannotLockOwnAccountException with a custom message and cause.
     *
     * @param message the detailed error message
     * @param cause   the cause of the exception
     */
    public CannotLockOwnAccountException(String message, Throwable cause) {
        super(message, cause);
    }
}
