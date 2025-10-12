package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.Phone;
import com.bitbi.dfm.account.domain.Company;
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
        when(account.getPhone()).thenReturn(Phone.of("+1234567890"));
        when(account.getCompany()).thenReturn(Company.of("Acme Corp"));
        when(account.getIsActive()).thenReturn(true);
        when(account.getCreatedAt()).thenReturn(createdAt);

        // When
        AccountResponseDto dto = AccountResponseDto.fromEntity(account, 5);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.id());
        assertEquals("test@example.com", dto.email());
        assertEquals("Test Account", dto.name());
        assertEquals("+1234567890", dto.phone());
        assertEquals("Acme Corp", dto.company());
        assertEquals("active", dto.status());
        assertEquals(true, dto.isActive());  // Backward compatibility field
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
        AccountResponseDto dto = AccountResponseDto.fromEntity(account, 10);

        // Then
        assertNotNull(dto);
        // Verify DTO only contains safe fields (9 fields: id, email, name, phone, company, status, isActive, createdAt, maxConcurrentBatches)
        assertEquals(9, dto.getClass().getRecordComponents().length);
        // Verify no password-like field exists in DTO
        assertDoesNotThrow(() -> {
            dto.id();
            dto.email();
            dto.name();
            dto.phone();
            dto.company();
            dto.status();
            dto.isActive();
            dto.createdAt();
            dto.maxConcurrentBatches();
        });
    }

    @Test
    @DisplayName("fromEntity should populate both status and isActive fields for backward compatibility")
    void fromEntity_shouldPopulateBothStatusAndIsActiveFields() {
        // Given - active account
        Account activeAccount = mock(Account.class);
        when(activeAccount.getId()).thenReturn(UUID.randomUUID());
        when(activeAccount.getEmail()).thenReturn("active@example.com");
        when(activeAccount.getName()).thenReturn("Active Account");
        when(activeAccount.getPhone()).thenReturn(null);
        when(activeAccount.getCompany()).thenReturn(null);
        when(activeAccount.getIsActive()).thenReturn(true);
        when(activeAccount.getCreatedAt()).thenReturn(LocalDateTime.now());

        // When
        AccountResponseDto activeDto = AccountResponseDto.fromEntity(activeAccount, 5);

        // Then
        assertEquals("active", activeDto.status());
        assertEquals(true, activeDto.isActive());

        // Given - inactive account
        Account inactiveAccount = mock(Account.class);
        when(inactiveAccount.getId()).thenReturn(UUID.randomUUID());
        when(inactiveAccount.getEmail()).thenReturn("inactive@example.com");
        when(inactiveAccount.getName()).thenReturn("Inactive Account");
        when(inactiveAccount.getPhone()).thenReturn(null);
        when(inactiveAccount.getCompany()).thenReturn(null);
        when(inactiveAccount.getIsActive()).thenReturn(false);
        when(inactiveAccount.getCreatedAt()).thenReturn(LocalDateTime.now());

        // When
        AccountResponseDto inactiveDto = AccountResponseDto.fromEntity(inactiveAccount, 5);

        // Then
        assertEquals("inactive", inactiveDto.status());
        assertEquals(false, inactiveDto.isActive());
    }
}
