package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.upload.domain.UploadedFile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * DTO for uploaded file metadata (Admin UI API).
 * <p>
 * Contains metadata for a single file within a batch.
 * </p>
 *
 * @param id               File unique identifier
 * @param originalFileName Original filename as uploaded
 * @param s3Key            Full S3 key for file retrieval
 * @param fileSize         File size in bytes
 * @param contentType      MIME type
 * @param checksum         MD5 checksum
 * @param uploadedAt       Upload timestamp
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchAdminController
 */
@Schema(description = "Uploaded file metadata")
public record UploadedFileDto(
        @Schema(description = "File unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Original filename as uploaded", example = "data.csv")
        String originalFileName,

        @Schema(description = "Full S3 key for file retrieval", example = "987fcdeb-51a2-43f7-9c3d-123456789abc/example.com/2025-01-15/10-30/data.csv")
        String s3Key,

        @Schema(description = "File size in bytes", example = "1048576")
        Long fileSize,

        @Schema(description = "MIME type", example = "text/csv")
        String contentType,

        @Schema(description = "MD5 checksum", example = "5d41402abc4b2a76b9719d911017c592")
        String checksum,

        @Schema(description = "Upload timestamp (ISO-8601)", example = "2025-01-15T10:35:00Z")
        Instant uploadedAt
) {
    /**
     * Create DTO from UploadedFile entity.
     *
     * @param file UploadedFile entity
     * @return UploadedFileDto
     */
    public static UploadedFileDto fromEntity(UploadedFile file) {
        return new UploadedFileDto(
                file.getId(),
                file.getOriginalFileName(),
                file.getS3Key(),
                file.getFileSize(),
                file.getContentType(),
                file.getChecksum(),
                file.getUploadedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
