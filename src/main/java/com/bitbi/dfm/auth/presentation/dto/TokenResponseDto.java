package com.bitbi.dfm.auth.presentation.dto;

import com.bitbi.dfm.auth.domain.JwtToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for JWT token generation.
 *
 * Provides immutable representation of generated token with claims for API responses.
 *
 * FR-001: Structured response objects
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation
 *
 * @param token JWT token string
 * @param expiresAt Token expiration timestamp
 * @param siteId Site ID from token claims
 * @param domain Domain from token claims
 */
public record TokenResponseDto(
    String token,
    Instant expiresAt,
    UUID siteId,
    String domain
) {

    /**
     * Convert JwtToken value object to TokenResponseDto.
     *
     * Extracts relevant fields from JwtToken, excluding internal fields like issuedAt and accountId.
     *
     * @param jwtToken The JWT token value object to convert
     * @return TokenResponseDto with extracted claims
     */
    public static TokenResponseDto fromToken(JwtToken jwtToken) {
        return new TokenResponseDto(
            jwtToken.token(),
            jwtToken.expiresAt(),
            jwtToken.siteId(),
            jwtToken.domain()
        );
    }
}
