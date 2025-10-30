package com.bitbi.dfm.error.presentation.dto;

import com.bitbi.dfm.error.domain.ErrorLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ErrorLogResponseDto.
 */
@DisplayName("ErrorLogResponseDto Unit Tests")
class ErrorLogResponseDtoTest {

    @Test
    @DisplayName("fromEntity should map all fields")
    void fromEntity_shouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        ErrorLog errorLog = mock(ErrorLog.class);
        when(errorLog.getId()).thenReturn(id);
        when(errorLog.getSiteId()).thenReturn(siteId);
        when(errorLog.getBatchId()).thenReturn(batchId);
        when(errorLog.getType()).thenReturn("ERROR");
        when(errorLog.getTitle()).thenReturn("Test error title");
        when(errorLog.getMessage()).thenReturn("Detailed error message");
        when(errorLog.getStackTrace()).thenReturn("com.example.Error at line 42");
        when(errorLog.getClientVersion()).thenReturn("1.0.0");
        when(errorLog.getMetadata()).thenReturn(metadata);
        when(errorLog.getOccurredAt()).thenReturn(occurredAt);

        // When
        ErrorLogResponseDto dto = ErrorLogResponseDto.fromEntity(errorLog);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.id());
        assertEquals(siteId, dto.siteId());
        assertEquals(batchId, dto.batchId());
        assertEquals("ERROR", dto.type());
        assertEquals("Test error title", dto.title());
        assertEquals("Detailed error message", dto.message());
        assertEquals("com.example.Error at line 42", dto.stackTrace());
        assertEquals("1.0.0", dto.clientVersion());
        assertNotNull(dto.metadata());
        assertEquals("value1", dto.metadata().get("key1"));
        assertEquals(occurredAt.toInstant(ZoneOffset.UTC), dto.occurredAt());
    }

    @Test
    @DisplayName("fromEntity should handle null metadata")
    void fromEntity_shouldHandleNullMetadata() {
        // Given
        ErrorLog errorLog = mock(ErrorLog.class);
        when(errorLog.getId()).thenReturn(UUID.randomUUID());
        when(errorLog.getSiteId()).thenReturn(UUID.randomUUID());
        when(errorLog.getBatchId()).thenReturn(UUID.randomUUID());
        when(errorLog.getType()).thenReturn("WARN");
        when(errorLog.getTitle()).thenReturn("Warning title");
        when(errorLog.getMessage()).thenReturn("Detailed warning message");
        when(errorLog.getStackTrace()).thenReturn(null);
        when(errorLog.getClientVersion()).thenReturn(null);
        when(errorLog.getMetadata()).thenReturn(null);
        when(errorLog.getOccurredAt()).thenReturn(LocalDateTime.now());

        // When
        ErrorLogResponseDto dto = ErrorLogResponseDto.fromEntity(errorLog);

        // Then
        assertNotNull(dto);
        // Metadata can be null
        assertNull(dto.stackTrace());
        assertNull(dto.clientVersion());
    }

    @Test
    @DisplayName("fromEntity should include JSONB metadata")
    void fromEntity_shouldIncludeJsonbMetadata() {
        // Given
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("custom_field", "custom_value");
        metadata.put("count", 42);

        ErrorLog errorLog = mock(ErrorLog.class);
        when(errorLog.getId()).thenReturn(UUID.randomUUID());
        when(errorLog.getSiteId()).thenReturn(UUID.randomUUID());
        when(errorLog.getBatchId()).thenReturn(UUID.randomUUID());
        when(errorLog.getType()).thenReturn("FATAL");
        when(errorLog.getTitle()).thenReturn("Fatal error");
        when(errorLog.getMessage()).thenReturn("Detailed fatal error");
        when(errorLog.getStackTrace()).thenReturn("java.lang.NullPointerException at line 42");
        when(errorLog.getClientVersion()).thenReturn("2.0.0");
        when(errorLog.getMetadata()).thenReturn(metadata);
        when(errorLog.getOccurredAt()).thenReturn(LocalDateTime.now());

        // When
        ErrorLogResponseDto dto = ErrorLogResponseDto.fromEntity(errorLog);

        // Then
        assertNotNull(dto);
        assertEquals("java.lang.NullPointerException at line 42", dto.stackTrace());
        assertEquals("2.0.0", dto.clientVersion());
        assertNotNull(dto.metadata());
        assertEquals("custom_value", dto.metadata().get("custom_field"));
        assertEquals(42, dto.metadata().get("count"));
    }
}
