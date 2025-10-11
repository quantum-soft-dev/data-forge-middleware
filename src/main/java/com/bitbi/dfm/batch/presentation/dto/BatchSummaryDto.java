package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Summary DTO for batch lists (Admin UI API).
 * <p>
 * Contains basic batch information without files list.
 * Used for paginated batch list endpoints.
 * </p>
 *
 * @param id                  Batch unique identifier
 * @param siteId              Site identifier
 * @param status              Batch status (IN_PROGRESS, COMPLETED, FAILED, etc.)
 * @param s3Path              S3 base path for batch files
 * @param uploadedFilesCount  Number of files uploaded
 * @param totalSize           Total size of all files in bytes
 * @param hasErrors           Whether batch has any errors
 * @param startedAt           Batch start timestamp
 * @param completedAt         Batch completion timestamp (null if in progress)
 * @param createdAt           Batch creation timestamp
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchAdminController
 */
@Schema(description = "Batch summary for list view")
public record BatchSummaryDto(
        @Schema(description = "Batch unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Site identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID siteId,

        @Schema(description = "Batch status", example = "IN_PROGRESS")
        String status,

        @Schema(description = "S3 base path for batch files", example = "987fcdeb-51a2-43f7-9c3d-123456789abc/example.com/2025-01-15/10-30/")
        String s3Path,

        @Schema(description = "Number of files uploaded", example = "15")
        Integer uploadedFilesCount,

        @Schema(description = "Total size of all files in bytes", example = "20971520")
        Long totalSize,

        @Schema(description = "Whether batch has any errors", example = "false")
        Boolean hasErrors,

        @Schema(description = "Batch start timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant startedAt,

        @Schema(description = "Batch completion timestamp (ISO-8601, null if in progress)", example = "2025-01-15T11:15:00Z")
        Instant completedAt,

        @Schema(description = "Batch creation timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant createdAt
) {
    /**
     * Create DTO from Batch entity.
     *
     * @param batch Batch entity
     * @return BatchSummaryDto
     */
    public static BatchSummaryDto fromEntity(Batch batch) {
        return new BatchSummaryDto(
                batch.getId(),
                batch.getSiteId(),
                batch.getStatus().toString(),
                batch.getS3Path(),
                batch.getUploadedFilesCount(),
                batch.getTotalSize(),
                batch.getHasErrors(),
                batch.getStartedAt().toInstant(ZoneOffset.UTC),
                batch.getCompletedAt() != null ? batch.getCompletedAt().toInstant(ZoneOffset.UTC) : null,
                batch.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
