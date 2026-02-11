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
 * <p>
 * Auth V2: domain field is nullable (new tokens don't include domain).
 * </p>
 *
 * @author Data Forge Team
 * @version 2.0.0
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
     * Domain is nullable for Auth V2 tokens.
     */
    public JwtToken {
        Objects.requireNonNull(token, "Token cannot be null");
        Objects.requireNonNull(issuedAt, "IssuedAt cannot be null");
        Objects.requireNonNull(expiresAt, "ExpiresAt cannot be null");
        Objects.requireNonNull(siteId, "SiteId cannot be null");
        Objects.requireNonNull(accountId, "AccountId cannot be null");
        // domain is nullable for Auth V2

        if (token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be blank");
        }

        if (expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("ExpiresAt must be after IssuedAt");
        }
    }

    /**
     * Create JWT token without domain (Auth V2).
     *
     * @param tokenString       JWT token string
     * @param siteId            site identifier
     * @param accountId         account identifier
     * @param expirationSeconds expiration duration in seconds
     * @return new JwtToken without domain
     */
    public static JwtToken create(String tokenString, UUID siteId, UUID accountId, long expirationSeconds) {
        Instant now = Instant.now();
        Instant expires = now.plus(expirationSeconds, ChronoUnit.SECONDS);
        return new JwtToken(tokenString, now, expires, siteId, accountId, null);
    }

    /**
     * Create JWT token with domain (legacy).
     *
     * @deprecated Use {@link #create(String, UUID, UUID, long)} instead
     */
    @Deprecated
    public static JwtToken createWithDomain(String tokenString, UUID siteId, UUID accountId, String domain, long expirationSeconds) {
        Instant now = Instant.now();
        Instant expires = now.plus(expirationSeconds, ChronoUnit.SECONDS);
        return new JwtToken(tokenString, now, expires, siteId, accountId, domain);
    }

    /**
     * Create new JWT token with default expiration (24 hours) and domain (legacy).
     *
     * @deprecated Use {@link #create(String, UUID, UUID, long)} instead
     */
    @Deprecated
    public static JwtToken create(String tokenString, UUID siteId, UUID accountId, String domain) {
        Instant now = Instant.now();
        Instant expires = now.plus(DEFAULT_EXPIRATION_SECONDS, ChronoUnit.SECONDS);
        return new JwtToken(tokenString, now, expires, siteId, accountId, domain);
    }

    /**
     * Create JWT token with custom expiration duration and domain (legacy).
     *
     * @deprecated Use {@link #create(String, UUID, UUID, long)} instead
     */
    @Deprecated
    public static JwtToken create(String tokenString, UUID siteId, UUID accountId, String domain, long expirationSeconds) {
        Instant now = Instant.now();
        Instant expires = now.plus(expirationSeconds, ChronoUnit.SECONDS);
        return new JwtToken(tokenString, now, expires, siteId, accountId, domain);
    }

    /**
     * Check if token is expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if token is still valid.
     */
    public boolean isValid() {
        return !isExpired();
    }

    /**
     * Get remaining validity duration in seconds.
     */
    public long getExpiresInSeconds() {
        if (isExpired()) {
            return 0;
        }
        return ChronoUnit.SECONDS.between(Instant.now(), expiresAt);
    }

    /**
     * Get total expiration duration in seconds.
     */
    public long getExpirationDuration() {
        return ChronoUnit.SECONDS.between(issuedAt, expiresAt);
    }
}
