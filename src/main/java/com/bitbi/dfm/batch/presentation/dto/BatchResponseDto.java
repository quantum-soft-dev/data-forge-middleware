package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for batch operations.
 *
 * Provides immutable, type-safe representation of batch data for API responses.
 * Converts domain entity timestamps from LocalDateTime to Instant for consistent API contract.
 *
 * FR-001: Structured response objects instead of Map<String, Object>
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation from entity
 *
 * @param id Unique batch identifier
 * @param batchId Alias for id (backward compatibility)
 * @param siteId Site that owns this batch
 * @param status Current batch status (IN_PROGRESS, COMPLETED, FAILED, CANCELLED, NOT_COMPLETED)
 * @param s3Path S3 path prefix for uploaded files
 * @param uploadedFilesCount Number of files uploaded to this batch
 * @param totalSize Total size of all uploaded files in bytes
 * @param hasErrors Whether batch has associated error logs
 * @param startedAt Batch start timestamp
 * @param completedAt Batch completion timestamp (null if still in progress)
 */
public record BatchResponseDto(
    UUID id,
    UUID batchId,
    UUID siteId,
    String status,
    String s3Path,
    Integer uploadedFilesCount,
    Long totalSize,
    Boolean hasErrors,
    Instant startedAt,
    Instant completedAt // Nullable - null for active batches
) {

    /**
     * Convert Batch domain entity to BatchResponseDto.
     *
     * Maps all fields from entity to DTO, converting:
     * - BatchStatus enum to string representation
     * - LocalDateTime timestamps to Instant (UTC)
     * - Null completedAt preserved for active batches
     *
     * @param batch The domain entity to convert
     * @return BatchResponseDto with all fields mapped
     */
    public static BatchResponseDto fromEntity(Batch batch) {
        return new BatchResponseDto(
            batch.getId(),
            batch.getId(), // batchId is an alias for id
            batch.getSiteId(),
            batch.getStatus().name(), // Convert enum to string
            batch.getS3Path(),
            batch.getUploadedFilesCount(),
            batch.getTotalSize(),
            batch.getHasErrors(),
            batch.getStartedAt().toInstant(ZoneOffset.UTC), // Convert LocalDateTime to Instant
            batch.getCompletedAt() != null
                ? batch.getCompletedAt().toInstant(ZoneOffset.UTC)
                : null
        );
    }
}
