package com.bitbi.dfm.auth.infrastructure;

import com.bitbi.dfm.auth.domain.JwtToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtTokenProvider.
 */
@DisplayName("JwtTokenProvider Unit Tests")
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private UUID testSiteId;
    private UUID testAccountId;
    private String testDomain;

    // Must be at least 256 bits (32 characters) for HS256
    private static final String TEST_SECRET = "test-secret-key-that-is-long-enough-for-hs256-algorithm";
    private static final long TEST_EXPIRATION = 3600L; // 1 hour

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, TEST_EXPIRATION);
        testSiteId = UUID.randomUUID();
        testAccountId = UUID.randomUUID();
        testDomain = "test.example.com";
    }

    @Test
    @DisplayName("Should generate valid JWT token with correct claims")
    void shouldGenerateValidJwtTokenWithCorrectClaims() {
        // When
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // Then
        assertNotNull(token);
        assertNotNull(token.token());
        assertEquals(testSiteId, token.siteId());
        assertEquals(testAccountId, token.accountId());
        assertEquals(testDomain, token.domain());
        assertEquals(TEST_EXPIRATION, token.getExpirationDuration());
    }

    @Test
    @DisplayName("Should validate token and extract claims")
    void shouldValidateTokenAndExtractClaims() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        Claims claims = jwtTokenProvider.validateToken(token.token());

        // Then
        assertNotNull(claims);
        assertEquals(testSiteId.toString(), claims.getSubject());
        assertEquals(testSiteId.toString(), claims.get("siteId", String.class));
        assertEquals(testAccountId.toString(), claims.get("accountId", String.class));
        assertEquals(testDomain, claims.get("domain", String.class));
    }

    @Test
    @DisplayName("Should extract site ID from valid token")
    void shouldExtractSiteIdFromValidToken() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        UUID extractedSiteId = jwtTokenProvider.extractSiteId(token.token());

        // Then
        assertEquals(testSiteId, extractedSiteId);
    }

    @Test
    @DisplayName("Should extract account ID from valid token")
    void shouldExtractAccountIdFromValidToken() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        UUID extractedAccountId = jwtTokenProvider.extractAccountId(token.token());

        // Then
        assertEquals(testAccountId, extractedAccountId);
    }

    @Test
    @DisplayName("Should extract domain from valid token")
    void shouldExtractDomainFromValidToken() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        String extractedDomain = jwtTokenProvider.extractDomain(token.token());

        // Then
        assertEquals(testDomain, extractedDomain);
    }

    @Test
    @DisplayName("Should throw exception for invalid token")
    void shouldThrowExceptionForInvalidToken() {
        // Given
        String invalidToken = "invalid.jwt.token";

        // When & Then
        assertThrows(JwtTokenProvider.InvalidTokenException.class, () -> {
            jwtTokenProvider.validateToken(invalidToken);
        });
    }

    @Test
    @DisplayName("Should throw exception for malformed token")
    void shouldThrowExceptionForMalformedToken() {
        // Given
        String malformedToken = "not-a-jwt-token";

        // When & Then
        assertThrows(JwtTokenProvider.InvalidTokenException.class, () -> {
            jwtTokenProvider.validateToken(malformedToken);
        });
    }

    @Test
    @DisplayName("Should throw exception when extracting site ID from invalid token")
    void shouldThrowExceptionWhenExtractingSiteIdFromInvalidToken() {
        // Given
        String invalidToken = "invalid.jwt.token";

        // When & Then
        assertThrows(JwtTokenProvider.InvalidTokenException.class, () -> {
            jwtTokenProvider.extractSiteId(invalidToken);
        });
    }

    @Test
    @DisplayName("Should return false for non-expired token")
    void shouldReturnFalseForNonExpiredToken() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        boolean isExpired = jwtTokenProvider.isExpired(token.token());

        // Then
        assertFalse(isExpired);
    }

    @Test
    @DisplayName("Should throw exception for invalid token")
    void shouldThrowExceptionForInvalidTokenWhenValidating() {
        // Given
        String invalidToken = "invalid.jwt.token";

        // When & Then
        assertThrows(JwtTokenProvider.InvalidTokenException.class, () -> {
            jwtTokenProvider.isExpired(invalidToken);
        });
    }

    @Test
    @DisplayName("Should generate different tokens for different sites")
    void shouldGenerateDifferentTokensForDifferentSites() {
        // Given
        UUID siteId1 = UUID.randomUUID();
        UUID siteId2 = UUID.randomUUID();

        // When
        JwtToken token1 = jwtTokenProvider.generateToken(siteId1, testAccountId, testDomain);
        JwtToken token2 = jwtTokenProvider.generateToken(siteId2, testAccountId, testDomain);

        // Then
        assertNotEquals(token1.token(), token2.token());
    }

    @Test
    @DisplayName("Should include issued at timestamp in token")
    void shouldIncludeIssuedAtTimestampInToken() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        Claims claims = jwtTokenProvider.validateToken(token.token());

        // Then
        assertNotNull(claims.getIssuedAt());
    }

    @Test
    @DisplayName("Should include expiration timestamp in token")
    void shouldIncludeExpirationTimestampInToken() {
        // Given
        JwtToken token = jwtTokenProvider.generateToken(testSiteId, testAccountId, testDomain);

        // When
        Claims claims = jwtTokenProvider.validateToken(token.token());

        // Then
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().getTime() > claims.getIssuedAt().getTime());
    }

    @Test
    @DisplayName("InvalidTokenException should have cause")
    void invalidTokenExceptionShouldHaveCause() {
        // Given
        JwtException cause = new JwtException("Test cause");

        // When
        JwtTokenProvider.InvalidTokenException exception =
                new JwtTokenProvider.InvalidTokenException("Test message", cause);

        // Then
        assertEquals("Test message", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    @DisplayName("Should use correct expiration time from configuration")
    void shouldUseCorrectExpirationTimeFromConfiguration() {
        // Given
        long customExpiration = 7200L; // 2 hours
        JwtTokenProvider customProvider = new JwtTokenProvider(TEST_SECRET, customExpiration);

        // When
        JwtToken token = customProvider.generateToken(testSiteId, testAccountId, testDomain);

        // Then
        assertEquals(customExpiration, token.getExpirationDuration());
    }
}
