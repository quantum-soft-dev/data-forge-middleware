package com.bitbi.dfm.auth.presentation.dto;

import com.bitbi.dfm.auth.domain.JwtToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for JWT token generation (Auth V2).
 *
 * @param token JWT token string
 * @param expiresAt Token expiration timestamp
 * @param siteId Site ID from token claims
 */
public record TokenResponseDto(
    String token,
    Instant expiresAt,
    UUID siteId
) {

    /**
     * Convert JwtToken value object to TokenResponseDto.
     *
     * @param jwtToken The JWT token value object to convert
     * @return TokenResponseDto with extracted claims
     */
    public static TokenResponseDto fromToken(JwtToken jwtToken) {
        return new TokenResponseDto(
            jwtToken.token(),
            jwtToken.expiresAt(),
            jwtToken.siteId()
        );
    }
}
