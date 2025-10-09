package com.bitbi.dfm.upload.presentation.dto;

import com.bitbi.dfm.upload.domain.UploadedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for FileUploadResponseDto.
 */
@DisplayName("FileUploadResponseDto Unit Tests")
class FileUploadResponseDtoTest {

    @Test
    @DisplayName("fromEntity should map all fields")
    void fromEntity_shouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        LocalDateTime uploadedAt = LocalDateTime.now();

        UploadedFile uploadedFile = mock(UploadedFile.class);
        when(uploadedFile.getId()).thenReturn(id);
        when(uploadedFile.getBatchId()).thenReturn(batchId);
        when(uploadedFile.getOriginalFileName()).thenReturn("test-file.csv");
        when(uploadedFile.getS3Key()).thenReturn("s3://bucket/path/test-file.csv");
        when(uploadedFile.getFileSize()).thenReturn(2048L);
        when(uploadedFile.getContentType()).thenReturn("text/csv");
        when(uploadedFile.getChecksum()).thenReturn("abc123def456");
        when(uploadedFile.getUploadedAt()).thenReturn(uploadedAt);

        // When
        FileUploadResponseDto dto = FileUploadResponseDto.fromEntity(uploadedFile);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.id());
        assertEquals(batchId, dto.batchId());
        assertEquals("test-file.csv", dto.filename());
        assertEquals("s3://bucket/path/test-file.csv", dto.s3Key());
        assertEquals(2048L, dto.fileSize());
        assertEquals("text/csv", dto.contentType());
        assertEquals("abc123def456", dto.checksum());
        assertEquals(uploadedAt.toInstant(ZoneOffset.UTC), dto.uploadedAt());
    }
}
