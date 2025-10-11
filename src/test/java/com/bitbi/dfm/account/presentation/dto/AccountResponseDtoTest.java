package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AccountResponseDto.
 */
@DisplayName("AccountResponseDto Unit Tests")
class AccountResponseDtoTest {

    @Test
    @DisplayName("fromEntity should map all fields")
    void fromEntity_shouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(id);
        when(account.getEmail()).thenReturn("test@example.com");
        when(account.getName()).thenReturn("Test Account");
        when(account.getPhone()).thenReturn("+1234567890");
        when(account.getCompany()).thenReturn("Acme Corp");
        when(account.getIsActive()).thenReturn(true);
        when(account.getCreatedAt()).thenReturn(createdAt);

        // When
        AccountResponseDto dto = AccountResponseDto.fromEntity(account);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.id());
        assertEquals("test@example.com", dto.email());
        assertEquals("Test Account", dto.name());
        assertEquals("+1234567890", dto.phone());
        assertEquals("Acme Corp", dto.company());
        assertEquals("active", dto.status());
        assertEquals(createdAt.toInstant(ZoneOffset.UTC), dto.createdAt());
        assertEquals(5, dto.maxConcurrentBatches());
    }

    @Test
    @DisplayName("fromEntity should exclude sensitive fields")
    void fromEntity_shouldExcludeSensitiveFields() {
        // Given
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(UUID.randomUUID());
        when(account.getEmail()).thenReturn("admin@example.com");
        when(account.getName()).thenReturn("Admin Account");
        when(account.getPhone()).thenReturn(null);
        when(account.getCompany()).thenReturn(null);
        when(account.getIsActive()).thenReturn(true);
        when(account.getCreatedAt()).thenReturn(LocalDateTime.now());
        // Note: Account entity may have password or other sensitive fields
        // These should NOT be exposed in the DTO

        // When
        AccountResponseDto dto = AccountResponseDto.fromEntity(account);

        // Then
        assertNotNull(dto);
        // Verify DTO only contains safe fields
        assertEquals(8, dto.getClass().getRecordComponents().length);
        // Verify no password-like field exists in DTO
        assertDoesNotThrow(() -> {
            dto.id();
            dto.email();
            dto.name();
            dto.phone();
            dto.company();
            dto.status();
            dto.createdAt();
            dto.maxConcurrentBatches();
        });
    }
}
