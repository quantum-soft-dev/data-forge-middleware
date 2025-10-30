package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Detailed DTO for batch retrieval (Admin UI API).
 * <p>
 * Extends batch summary with site domain and full files list.
 * Used for GET /api/admin/batches/{id} endpoint.
 * </p>
 *
 * @param id                  Batch unique identifier
 * @param siteId              Site identifier
 * @param siteDomain          Site domain name
 * @param status              Batch status (IN_PROGRESS, COMPLETED, FAILED, etc.)
 * @param s3Path              S3 base path for batch files
 * @param uploadedFilesCount  Number of files uploaded
 * @param totalSize           Total size of all files in bytes
 * @param hasErrors           Whether batch has any errors
 * @param startedAt           Batch start timestamp
 * @param completedAt         Batch completion timestamp (null if in progress)
 * @param createdAt           Batch creation timestamp
 * @param files               List of uploaded file metadata
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchAdminController
 */
@Schema(description = "Batch detail response with files list")
public record BatchDetailResponseDto(
        @Schema(description = "Batch unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Site identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID siteId,

        @Schema(description = "Site domain name", example = "example.com")
        String siteDomain,

        @Schema(description = "Batch status", example = "COMPLETED")
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
        Instant createdAt,

        @Schema(description = "List of uploaded file metadata")
        List<UploadedFileDto> files
) {
    /**
     * Create DTO from Batch entity, Site entity, and UploadedFile list.
     *
     * @param batch Batch entity
     * @param site  Site entity (may be null if site deleted)
     * @param files List of UploadedFile entities
     * @return BatchDetailResponseDto
     */
    public static BatchDetailResponseDto fromEntityAndFiles(Batch batch, Site site, List<UploadedFile> files) {
        List<UploadedFileDto> fileDtos = files.stream()
                .map(UploadedFileDto::fromEntity)
                .toList();

        return new BatchDetailResponseDto(
                batch.getId(),
                batch.getSiteId(),
                site != null ? site.getDomain() : "unknown",
                batch.getStatus().toString(),
                batch.getS3Path(),
                batch.getUploadedFilesCount(),
                batch.getTotalSize(),
                batch.getHasErrors(),
                batch.getStartedAt().toInstant(ZoneOffset.UTC),
                batch.getCompletedAt() != null ? batch.getCompletedAt().toInstant(ZoneOffset.UTC) : null,
                batch.getCreatedAt().toInstant(ZoneOffset.UTC),
                fileDtos
        );
    }
}
