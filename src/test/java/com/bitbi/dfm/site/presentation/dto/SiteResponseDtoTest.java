package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.domain.Site;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SiteResponseDto.
 */
@DisplayName("SiteResponseDto Unit Tests")
class SiteResponseDtoTest {

    @Test
    @DisplayName("fromEntity should map all fields")
    void fromEntity_shouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();

        Site site = mock(Site.class);
        when(site.getId()).thenReturn(id);
        when(site.getAccountId()).thenReturn(accountId);
        when(site.getDomain()).thenReturn("example.com");
        when(site.getDisplayName()).thenReturn("Example Site");
        when(site.getIsActive()).thenReturn(true);
        when(site.getCreatedAt()).thenReturn(createdAt);

        // When
        SiteResponseDto dto = SiteResponseDto.fromEntity(site);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.id());
        assertEquals(accountId, dto.accountId());
        assertEquals("example.com", dto.domain());
        assertEquals("Example Site", dto.name());
        assertEquals(true, dto.isActive());
        assertEquals(createdAt.toInstant(ZoneOffset.UTC), dto.createdAt());
    }

    @Test
    @DisplayName("fromEntity should exclude clientSecret")
    void fromEntity_shouldExcludeClientSecret() {
        // Given
        Site site = mock(Site.class);
        when(site.getId()).thenReturn(UUID.randomUUID());
        when(site.getAccountId()).thenReturn(UUID.randomUUID());
        when(site.getDomain()).thenReturn("secure.example.com");
        when(site.getDisplayName()).thenReturn("Secure Site");
        when(site.getIsActive()).thenReturn(true);
        when(site.getCreatedAt()).thenReturn(LocalDateTime.now());
        // Note: Site entity has clientSecretHash field which should NOT be exposed

        // When
        SiteResponseDto dto = SiteResponseDto.fromEntity(site);

        // Then
        assertNotNull(dto);
        // Verify DTO only contains safe fields (6 fields total)
        assertEquals(6, dto.getClass().getRecordComponents().length);
        // Verify no clientSecret-like field exists in DTO
        assertDoesNotThrow(() -> {
            dto.id();
            dto.accountId();
            dto.domain();
            dto.name();
            dto.isActive();
            dto.createdAt();
        });
    }
}
