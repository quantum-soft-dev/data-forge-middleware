package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.upload.domain.UploadedFile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * DTO for file metadata in client API (Upload History feature).
 * <p>
 * Simplified version of UploadedFileDto for end-users.
 * Does NOT include s3Key or checksum (security - not exposed to frontend).
 * </p>
 *
 * @param id               File unique identifier for download requests
 * @param originalFileName Display name (e.g., "sales-2024.csv.gz")
 * @param fileSize         File size in bytes (convert to KB/MB in frontend)
 * @param uploadedAt       Upload timestamp
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchHistoryController
 */
@Schema(description = "File metadata for upload history")
public record FileMetadataDto(
        @Schema(description = "File unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Original filename as uploaded", example = "sales-2024.csv.gz")
        String originalFileName,

        @Schema(description = "File size in bytes", example = "2621440")
        Long fileSize,

        @Schema(description = "Upload timestamp (ISO-8601)", example = "2025-01-15T10:35:00Z")
        Instant uploadedAt
) {
    /**
     * Create DTO from UploadedFile entity.
     * <p>
     * Excludes s3Key and checksum for security.
     * </p>
     *
     * @param file UploadedFile entity
     * @return FileMetadataDto
     */
    public static FileMetadataDto fromEntity(UploadedFile file) {
        return new FileMetadataDto(
                file.getId(),
                file.getOriginalFileName(),
                file.getFileSize(),
                file.getUploadedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
