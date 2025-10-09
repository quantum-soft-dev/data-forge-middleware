package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BatchResponseDto.
 */
@DisplayName("BatchResponseDto Unit Tests")
class BatchResponseDtoTest {

    @Test
    @DisplayName("fromEntity should map all fields for active batch")
    void fromEntity_shouldMapAllFields() {
        // Given
        UUID id = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.now();

        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(id);
        when(batch.getSiteId()).thenReturn(siteId);
        when(batch.getStatus()).thenReturn(BatchStatus.IN_PROGRESS);
        when(batch.getS3Path()).thenReturn("s3://bucket/path");
        when(batch.getUploadedFilesCount()).thenReturn(5);
        when(batch.getTotalSize()).thenReturn(1024L);
        when(batch.getHasErrors()).thenReturn(false);
        when(batch.getStartedAt()).thenReturn(startedAt);
        when(batch.getCompletedAt()).thenReturn(null); // Active batch has no completedAt

        // When
        BatchResponseDto dto = BatchResponseDto.fromEntity(batch);

        // Then
        assertNotNull(dto);
        assertEquals(id, dto.id());
        assertEquals(id, dto.batchId()); // batchId is an alias for id
        assertEquals(siteId, dto.siteId());
        assertEquals("IN_PROGRESS", dto.status());
        assertEquals("s3://bucket/path", dto.s3Path());
        assertEquals(5, dto.uploadedFilesCount());
        assertEquals(1024L, dto.totalSize());
        assertEquals(false, dto.hasErrors());
        assertEquals(startedAt.toInstant(ZoneOffset.UTC), dto.startedAt());
        assertNull(dto.completedAt()); // Should be null for active batch
    }

    @Test
    @DisplayName("fromEntity should map completedAt for completed batch")
    void fromEntity_shouldMapCompletedBatch() {
        // Given
        UUID id = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.now().minusHours(2);
        LocalDateTime completedAt = LocalDateTime.now();

        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(id);
        when(batch.getSiteId()).thenReturn(siteId);
        when(batch.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(batch.getS3Path()).thenReturn("s3://bucket/path");
        when(batch.getUploadedFilesCount()).thenReturn(10);
        when(batch.getTotalSize()).thenReturn(2048L);
        when(batch.getHasErrors()).thenReturn(true);
        when(batch.getStartedAt()).thenReturn(startedAt);
        when(batch.getCompletedAt()).thenReturn(completedAt);

        // When
        BatchResponseDto dto = BatchResponseDto.fromEntity(batch);

        // Then
        assertNotNull(dto);
        assertEquals("COMPLETED", dto.status());
        assertEquals(startedAt.toInstant(ZoneOffset.UTC), dto.startedAt());
        assertEquals(completedAt.toInstant(ZoneOffset.UTC), dto.completedAt());
        assertTrue(dto.hasErrors());
    }

    @Test
    @DisplayName("fromEntity should convert status enum to string")
    void fromEntity_shouldConvertStatusEnumToString() {
        // Given
        Batch inProgressBatch = createBatchWithStatus(BatchStatus.IN_PROGRESS);
        Batch completedBatch = createBatchWithStatus(BatchStatus.COMPLETED);
        Batch failedBatch = createBatchWithStatus(BatchStatus.FAILED);
        Batch cancelledBatch = createBatchWithStatus(BatchStatus.CANCELLED);
        Batch notCompletedBatch = createBatchWithStatus(BatchStatus.NOT_COMPLETED);

        // When
        BatchResponseDto inProgressDto = BatchResponseDto.fromEntity(inProgressBatch);
        BatchResponseDto completedDto = BatchResponseDto.fromEntity(completedBatch);
        BatchResponseDto failedDto = BatchResponseDto.fromEntity(failedBatch);
        BatchResponseDto cancelledDto = BatchResponseDto.fromEntity(cancelledBatch);
        BatchResponseDto notCompletedDto = BatchResponseDto.fromEntity(notCompletedBatch);

        // Then
        assertEquals("IN_PROGRESS", inProgressDto.status());
        assertEquals("COMPLETED", completedDto.status());
        assertEquals("FAILED", failedDto.status());
        assertEquals("CANCELLED", cancelledDto.status());
        assertEquals("NOT_COMPLETED", notCompletedDto.status());
    }

    private Batch createBatchWithStatus(BatchStatus status) {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batch.getSiteId()).thenReturn(UUID.randomUUID());
        when(batch.getStatus()).thenReturn(status);
        when(batch.getS3Path()).thenReturn("s3://bucket/path");
        when(batch.getUploadedFilesCount()).thenReturn(0);
        when(batch.getTotalSize()).thenReturn(0L);
        when(batch.getHasErrors()).thenReturn(false);
        when(batch.getStartedAt()).thenReturn(LocalDateTime.now());
        when(batch.getCompletedAt()).thenReturn(null);
        return batch;
    }
}
