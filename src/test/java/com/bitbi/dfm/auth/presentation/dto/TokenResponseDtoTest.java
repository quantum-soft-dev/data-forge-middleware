package com.bitbi.dfm.auth.presentation.dto;

import com.bitbi.dfm.auth.domain.JwtToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for TokenResponseDto.
 */
@DisplayName("TokenResponseDto Unit Tests")
class TokenResponseDtoTest {

    @Test
    @DisplayName("fromToken should extract all claims")
    void fromToken_shouldExtractAllClaims() {
        // Given
        UUID siteId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String tokenString = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...";
        String domain = "example.com";

        JwtToken jwtToken = mock(JwtToken.class);
        when(jwtToken.token()).thenReturn(tokenString);
        when(jwtToken.expiresAt()).thenReturn(expiresAt);
        when(jwtToken.siteId()).thenReturn(siteId);
        when(jwtToken.domain()).thenReturn(domain);

        // When
        TokenResponseDto dto = TokenResponseDto.fromToken(jwtToken);

        // Then
        assertNotNull(dto);
        assertEquals(tokenString, dto.token());
        assertEquals(expiresAt, dto.expiresAt());
        assertEquals(siteId, dto.siteId());
        assertEquals(domain, dto.domain());
    }
}
