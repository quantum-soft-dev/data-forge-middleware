package com.bitbi.dfm.auth.infrastructure;

import com.bitbi.dfm.auth.domain.JwtToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT token provider using HMAC-SHA256 signing.
 * <p>
 * Generates and validates JWT tokens with custom claims:
 * - siteId: UUID of authenticated site
 * - accountId: UUID of account owning the site
 * - domain: site domain name
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * Generate JWT token for site authentication.
     *
     * @param siteId    site identifier
     * @param accountId account identifier
     * @param domain    site domain name
     * @return JWT token with claims
     */
    public JwtToken generateToken(UUID siteId, UUID accountId, String domain) {
        String tokenString = Jwts.builder()
                .subject(siteId.toString())
                .claim("siteId", siteId.toString())
                .claim("accountId", accountId.toString())
                .claim("domain", domain)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationSeconds * 1000))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        return JwtToken.create(tokenString, siteId, accountId, domain, expirationSeconds);
    }

    /**
     * Validate JWT token and extract claims.
     *
     * @param token JWT token string
     * @return claims from token
     * @throws JwtException if token is invalid or expired
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new InvalidTokenException("Invalid or expired JWT token", e);
        }
    }

    /**
     * Extract site ID from token.
     *
     * @param token JWT token string
     * @return site UUID
     * @throws InvalidTokenException if token is invalid
     */
    public UUID extractSiteId(String token) {
        Claims claims = validateToken(token);
        String siteIdStr = claims.get("siteId", String.class);
        return UUID.fromString(siteIdStr);
    }

    /**
     * Extract account ID from token.
     *
     * @param token JWT token string
     * @return account UUID
     * @throws InvalidTokenException if token is invalid
     */
    public UUID extractAccountId(String token) {
        Claims claims = validateToken(token);
        String accountIdStr = claims.get("accountId", String.class);
        return UUID.fromString(accountIdStr);
    }

    /**
     * Extract domain from token.
     *
     * @param token JWT token string
     * @return site domain
     * @throws InvalidTokenException if token is invalid
     */
    public String extractDomain(String token) {
        Claims claims = validateToken(token);
        return claims.get("domain", String.class);
    }

    /**
     * Check if token is expired.
     *
     * @param token JWT token string
     * @return true if token is expired
     */
    public boolean isExpired(String token) {
        try {
            Claims claims = validateToken(token);
            return claims.getExpiration().before(new Date());
        } catch (JwtException e) {
            return true;
        }
    }

    /**
     * Exception thrown when JWT token validation fails.
     */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
