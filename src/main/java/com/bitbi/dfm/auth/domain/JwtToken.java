package com.bitbi.dfm.auth.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing JWT token with expiration logic.
 * <p>
 * Encapsulates token string, expiration time, and claims.
 * Immutable and validated at construction time.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record JwtToken(
        String token,
        Instant issuedAt,
        Instant expiresAt,
        UUID siteId,
        UUID accountId,
        String domain
) {

    private static final long DEFAULT_EXPIRATION_SECONDS = 86400L; // 24 hours

    /**
     * Constructs JwtToken with validation.
     *
     * @param token     JWT token string
     * @param issuedAt  token issue timestamp
     * @param expiresAt token expiration timestamp
     * @param siteId    site identifier
     * @param accountId account identifier
     * @param domain    site domain
     * @throws IllegalArgumentException if any parameter is null or invalid
     */
    public JwtToken {
        Objects.requireNonNull(token, "Token cannot be null");
        Objects.requireNonNull(issuedAt, "IssuedAt cannot be null");
        Objects.requireNonNull(expiresAt, "ExpiresAt cannot be null");
        Objects.requireNonNull(siteId, "SiteId cannot be null");
        Objects.requireNonNull(accountId, "AccountId cannot be null");
        Objects.requireNonNull(domain, "Domain cannot be null");

        if (token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be blank");
        }

        if (expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("ExpiresAt must be after IssuedAt");
        }
    }

    /**
     * Create new JWT token with default expiration (24 hours).
     *
     * @param tokenString JWT token string
     * @param siteId      site identifier
     * @param accountId   account identifier
     * @param domain      site domain
     * @return new JwtToken
     */
    public static JwtToken create(String tokenString, UUID siteId, UUID accountId, String domain) {
        Instant now = Instant.now();
        Instant expires = now.plus(DEFAULT_EXPIRATION_SECONDS, ChronoUnit.SECONDS);
        return new JwtToken(tokenString, now, expires, siteId, accountId, domain);
    }

    /**
     * Create JWT token with custom expiration duration.
     *
     * @param tokenString      JWT token string
     * @param siteId           site identifier
     * @param accountId        account identifier
     * @param domain           site domain
     * @param expirationSeconds expiration duration in seconds
     * @return new JwtToken
     */
    public static JwtToken create(String tokenString, UUID siteId, UUID accountId, String domain, long expirationSeconds) {
        Instant now = Instant.now();
        Instant expires = now.plus(expirationSeconds, ChronoUnit.SECONDS);
        return new JwtToken(tokenString, now, expires, siteId, accountId, domain);
    }

    /**
     * Check if token is expired.
     *
     * @return true if current time is after expiresAt
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if token is still valid.
     *
     * @return true if current time is before expiresAt
     */
    public boolean isValid() {
        return !isExpired();
    }

    /**
     * Get remaining validity duration in seconds.
     *
     * @return seconds until expiration (0 if already expired)
     */
    public long getExpiresInSeconds() {
        if (isExpired()) {
            return 0;
        }
        return ChronoUnit.SECONDS.between(Instant.now(), expiresAt);
    }

    /**
     * Get total expiration duration in seconds.
     *
     * @return original expiration duration
     */
    public long getExpirationDuration() {
        return ChronoUnit.SECONDS.between(issuedAt, expiresAt);
    }
}
