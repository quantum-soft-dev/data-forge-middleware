package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Detailed batch DTO for client API (Upload History feature).
 * <p>
 * Extends batch summary with full file list using simplified FileMetadataDto.
 * Used for GET /api/dfc/batches/{id} endpoint.
 * </p>
 *
 * @param id                  Batch unique identifier
 * @param siteId              Site identifier
 * @param status              Batch status (IN_PROGRESS, COMPLETED, FAILED, etc.)
 * @param hasErrors           Whether batch has any errors
 * @param uploadedFilesCount  Number of files uploaded
 * @param totalSize           Total size of all files in bytes
 * @param startedAt           Batch start timestamp
 * @param completedAt         Batch completion timestamp (null if in progress)
 * @param files               List of file metadata (simplified for client)
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchHistoryController
 */
@Schema(description = "Batch detail with file list for upload history")
public record BatchDetailDto(
        @Schema(description = "Batch unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Site identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID siteId,

        @Schema(description = "Batch status", example = "COMPLETED")
        String status,

        @Schema(description = "Whether batch has any errors", example = "false")
        Boolean hasErrors,

        @Schema(description = "Number of files uploaded", example = "15")
        Integer uploadedFilesCount,

        @Schema(description = "Total size of all files in bytes", example = "20971520")
        Long totalSize,

        @Schema(description = "Batch start timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant startedAt,

        @Schema(description = "Batch completion timestamp (ISO-8601, null if in progress)", example = "2025-01-15T11:15:00Z")
        Instant completedAt,

        @Schema(description = "List of file metadata")
        List<FileMetadataDto> files
) {
    /**
     * Create DTO from Batch entity and UploadedFile list.
     *
     * @param batch Batch entity
     * @param files List of UploadedFile entities
     * @return BatchDetailDto
     */
    public static BatchDetailDto fromEntityAndFiles(Batch batch, List<UploadedFile> files) {
        List<FileMetadataDto> fileDtos = files.stream()
                .map(FileMetadataDto::fromEntity)
                .toList();

        return new BatchDetailDto(
                batch.getId(),
                batch.getSiteId(),
                batch.getStatus().toString(),
                batch.getHasErrors(),
                batch.getUploadedFilesCount(),
                batch.getTotalSize(),
                batch.getStartedAt().toInstant(ZoneOffset.UTC),
                batch.getCompletedAt() != null ? batch.getCompletedAt().toInstant(ZoneOffset.UTC) : null,
                fileDtos
        );
    }
}
