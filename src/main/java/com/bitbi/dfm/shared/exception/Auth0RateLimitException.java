package com.bitbi.dfm.shared.exception;

/**
 * Exception thrown when Auth0 Management API rate limit is exceeded (HTTP 429).
 * <p>
 * This exception should trigger HTTP 503 Service Unavailable response with Retry-After header.
 * Indicates the application is making too many requests to Auth0 Management API.
 * </p>
 * <p>
 * Auth0 Rate Limits:
 * - Paid tier: 15 requests/second sustained, 50 requests/second burst
 * - Free tier: 2 requests/second sustained, 10 requests/second burst
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public class Auth0RateLimitException extends RuntimeException {

    private final Integer retryAfterSeconds;

    public Auth0RateLimitException(String message) {
        super(message);
        this.retryAfterSeconds = null;
    }

    public Auth0RateLimitException(String message, Integer retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Auth0RateLimitException(String message, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = null;
    }

    public Auth0RateLimitException(String message, Integer retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Factory method for Auth0 rate limit errors with retry-after header.
     *
     * @param operation The operation that was rate-limited
     * @param retryAfterSeconds Time to wait before retrying (from X-RateLimit-Reset header)
     * @return New exception instance
     */
    public static Auth0RateLimitException forOperation(String operation, Integer retryAfterSeconds) {
        return new Auth0RateLimitException(
            String.format("Auth0 Management API rate limit exceeded for operation: %s. Retry after %d seconds.",
                operation, retryAfterSeconds != null ? retryAfterSeconds : 60),
            retryAfterSeconds
        );
    }
}
