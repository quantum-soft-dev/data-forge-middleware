package com.bitbi.dfm.auth.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object representing an Auth0 user ID.
 * <p>
 * Auth0 user IDs follow the format: {provider}|{alphanumeric}
 * Examples:
 * <ul>
 *   <li>auth0|507f1f77bcf86cd799439011 (Database connection)</li>
 *   <li>google-oauth2|115015365098652987163 (Google OAuth2)</li>
 *   <li>windowslive|4d35b10d6f5b07e8 (Microsoft Account)</li>
 * </ul>
 * </p>
 *
 * <h3>Validation Rules:</h3>
 * <ul>
 *   <li>Cannot be null or blank</li>
 *   <li>Cannot exceed 64 characters (database constraint)</li>
 *   <li>Must match pattern: {provider}|{user-id} where both parts are alphanumeric with hyphens allowed</li>
 * </ul>
 *
 * @param value the Auth0 user ID string
 * @author Data Forge Team
 * @version 1.0.0
 */
public record Auth0UserId(String value) {

    /**
     * Auth0 user ID pattern: {provider}|{alphanumeric}
     * Examples: auth0|abc123, google-oauth2|123, windowslive|xyz, auth0|test-user-3-abcde
     * Note: Both provider and user ID parts can contain hyphens
     */
    private static final Pattern AUTH0_USER_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9-]+\\|[a-zA-Z0-9-]+$");

    /**
     * Maximum length for Auth0 user IDs (matches database column constraint).
     */
    private static final int MAX_LENGTH = 64;

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if value is invalid
     */
    public Auth0UserId {
        Objects.requireNonNull(value, "Auth0 user ID cannot be null");

        if (value.isBlank()) {
            throw new IllegalArgumentException("Auth0 user ID cannot be blank");
        }

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Auth0 user ID cannot exceed " + MAX_LENGTH + " characters. Got: " + value.length()
            );
        }

        if (!AUTH0_USER_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Auth0 user ID must match format {provider}|{alphanumeric}. Got: " + value
            );
        }
    }

    /**
     * Factory method to create Auth0UserId from string with validation.
     *
     * @param value the Auth0 user ID string
     * @return Auth0UserId instance
     * @throws IllegalArgumentException if value is invalid
     */
    public static Auth0UserId of(String value) {
        return new Auth0UserId(value);
    }

    /**
     * Extract the provider name from the Auth0 user ID.
     * <p>
     * Examples:
     * <ul>
     *   <li>auth0|abc123 → auth0</li>
     *   <li>google-oauth2|123 → google-oauth2</li>
     * </ul>
     * </p>
     *
     * @return provider name
     */
    public String provider() {
        int separatorIndex = value.indexOf('|');
        if (separatorIndex == -1) {
            throw new IllegalStateException("Invalid Auth0 user ID format: " + value);
        }
        return value.substring(0, separatorIndex);
    }

    /**
     * Extract the user identifier from the Auth0 user ID.
     * <p>
     * Examples:
     * <ul>
     *   <li>auth0|abc123 → abc123</li>
     *   <li>google-oauth2|123 → 123</li>
     * </ul>
     * </p>
     *
     * @return user identifier
     */
    public String identifier() {
        int separatorIndex = value.indexOf('|');
        if (separatorIndex == -1) {
            throw new IllegalStateException("Invalid Auth0 user ID format: " + value);
        }
        return value.substring(separatorIndex + 1);
    }

    /**
     * Check if this is an Auth0 database connection user ID.
     *
     * @return true if provider is "auth0"
     */
    public boolean isDatabaseConnection() {
        return "auth0".equals(provider());
    }

    @Override
    public String toString() {
        return value;
    }
}
