package com.bitbi.dfm.shared.exception;

/**
 * Exception thrown when Auth0 Management API is unavailable or returns 5xx errors.
 * <p>
 * This exception should trigger HTTP 503 Service Unavailable response.
 * Typically indicates temporary Auth0 service issues or network problems.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class Auth0ServiceUnavailableException extends RuntimeException {

    public Auth0ServiceUnavailableException(String message) {
        super(message);
    }

    public Auth0ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Factory method for Auth0 Management API errors.
     *
     * @param operation The operation that failed (e.g., "create user", "block user")
     * @param cause The underlying exception
     * @return New exception instance
     */
    public static Auth0ServiceUnavailableException forOperation(String operation, Throwable cause) {
        return new Auth0ServiceUnavailableException(
            String.format("Auth0 Management API unavailable for operation: %s", operation),
            cause
        );
    }
}
