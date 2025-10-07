package com.bitbi.dfm.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtToken value object.
 */
@DisplayName("JwtToken Unit Tests")
class JwtTokenTest {

    private static final UUID TEST_SITE_ID = UUID.randomUUID();
    private static final UUID TEST_ACCOUNT_ID = UUID.randomUUID();
    private static final String TEST_DOMAIN = "test.example.com";
    private static final String TEST_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test";

    @Test
    @DisplayName("Should create JWT token with default expiration")
    void shouldCreateJwtTokenWithDefaultExpiration() {
        // When
        JwtToken token = JwtToken.create(TEST_TOKEN, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN);

        // Then
        assertNotNull(token);
        assertEquals(TEST_TOKEN, token.token());
        assertEquals(TEST_SITE_ID, token.siteId());
        assertEquals(TEST_ACCOUNT_ID, token.accountId());
        assertEquals(TEST_DOMAIN, token.domain());
        assertNotNull(token.issuedAt());
        assertNotNull(token.expiresAt());
        assertTrue(token.expiresAt().isAfter(token.issuedAt()));
        assertEquals(86400L, token.getExpirationDuration()); // 24 hours
    }

    @Test
    @DisplayName("Should create JWT token with custom expiration")
    void shouldCreateJwtTokenWithCustomExpiration() {
        // Given
        long customExpiration = 3600L; // 1 hour

        // When
        JwtToken token = JwtToken.create(TEST_TOKEN, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN, customExpiration);

        // Then
        assertNotNull(token);
        assertEquals(customExpiration, token.getExpirationDuration());
    }

    @Test
    @DisplayName("Should validate new token is not expired")
    void shouldValidateNewTokenIsNotExpired() {
        // Given
        JwtToken token = JwtToken.create(TEST_TOKEN, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN);

        // When & Then
        assertFalse(token.isExpired());
        assertTrue(token.isValid());
    }

    @Test
    @DisplayName("Should detect expired token")
    void shouldDetectExpiredToken() {
        // Given
        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.HOURS);

        // When
        JwtToken token = new JwtToken(TEST_TOKEN, issuedAt, expiresAt, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN);

        // Then
        assertTrue(token.isExpired());
        assertFalse(token.isValid());
    }

    @Test
    @DisplayName("Should calculate remaining time until expiration")
    void shouldCalculateRemainingTimeUntilExpiration() {
        // Given
        long expirationSeconds = 3600L; // 1 hour
        JwtToken token = JwtToken.create(TEST_TOKEN, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN, expirationSeconds);

        // When
        long remainingSeconds = token.getExpiresInSeconds();

        // Then
        assertTrue(remainingSeconds > 0);
        assertTrue(remainingSeconds <= expirationSeconds);
    }

    @Test
    @DisplayName("Should return zero remaining time for expired token")
    void shouldReturnZeroRemainingTimeForExpiredToken() {
        // Given
        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.HOURS);
        JwtToken token = new JwtToken(TEST_TOKEN, issuedAt, expiresAt, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN);

        // When
        long remainingSeconds = token.getExpiresInSeconds();

        // Then
        assertEquals(0, remainingSeconds);
    }

    @Test
    @DisplayName("Should throw exception when token is null")
    void shouldThrowExceptionWhenTokenIsNull() {
        // Given
        Instant now = Instant.now();
        Instant expires = now.plus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(NullPointerException.class, () ->
                new JwtToken(null, now, expires, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should throw exception when token is blank")
    void shouldThrowExceptionWhenTokenIsBlank() {
        // Given
        Instant now = Instant.now();
        Instant expires = now.plus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
                new JwtToken("   ", now, expires, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should throw exception when issuedAt is null")
    void shouldThrowExceptionWhenIssuedAtIsNull() {
        // Given
        Instant expires = Instant.now().plus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(NullPointerException.class, () ->
                new JwtToken(TEST_TOKEN, null, expires, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should throw exception when expiresAt is null")
    void shouldThrowExceptionWhenExpiresAtIsNull() {
        // Given
        Instant now = Instant.now();

        // When & Then
        assertThrows(NullPointerException.class, () ->
                new JwtToken(TEST_TOKEN, now, null, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should throw exception when siteId is null")
    void shouldThrowExceptionWhenSiteIdIsNull() {
        // Given
        Instant now = Instant.now();
        Instant expires = now.plus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(NullPointerException.class, () ->
                new JwtToken(TEST_TOKEN, now, expires, null, TEST_ACCOUNT_ID, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should throw exception when accountId is null")
    void shouldThrowExceptionWhenAccountIdIsNull() {
        // Given
        Instant now = Instant.now();
        Instant expires = now.plus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(NullPointerException.class, () ->
                new JwtToken(TEST_TOKEN, now, expires, TEST_SITE_ID, null, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should throw exception when domain is null")
    void shouldThrowExceptionWhenDomainIsNull() {
        // Given
        Instant now = Instant.now();
        Instant expires = now.plus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(NullPointerException.class, () ->
                new JwtToken(TEST_TOKEN, now, expires, TEST_SITE_ID, TEST_ACCOUNT_ID, null)
        );
    }

    @Test
    @DisplayName("Should throw exception when expiresAt is before issuedAt")
    void shouldThrowExceptionWhenExpiresAtIsBeforeIssuedAt() {
        // Given
        Instant now = Instant.now();
        Instant pastTime = now.minus(3600, ChronoUnit.SECONDS);

        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
                new JwtToken(TEST_TOKEN, now, pastTime, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN)
        );
    }

    @Test
    @DisplayName("Should correctly calculate expiration duration")
    void shouldCorrectlyCalculateExpirationDuration() {
        // Given
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(7200, ChronoUnit.SECONDS);
        JwtToken token = new JwtToken(TEST_TOKEN, issuedAt, expiresAt, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN);

        // When
        long duration = token.getExpirationDuration();

        // Then
        assertEquals(7200L, duration);
    }

    @Test
    @DisplayName("Should preserve all token properties")
    void shouldPreserveAllTokenProperties() {
        // Given
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(3600, ChronoUnit.SECONDS);

        // When
        JwtToken token = new JwtToken(TEST_TOKEN, issuedAt, expiresAt, TEST_SITE_ID, TEST_ACCOUNT_ID, TEST_DOMAIN);

        // Then
        assertEquals(TEST_TOKEN, token.token());
        assertEquals(issuedAt, token.issuedAt());
        assertEquals(expiresAt, token.expiresAt());
        assertEquals(TEST_SITE_ID, token.siteId());
        assertEquals(TEST_ACCOUNT_ID, token.accountId());
        assertEquals(TEST_DOMAIN, token.domain());
    }
}
