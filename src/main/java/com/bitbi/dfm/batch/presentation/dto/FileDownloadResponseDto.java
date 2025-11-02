package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * DTO for file download response with presigned URL.
 * <p>
 * Returns S3 presigned URL for direct client-to-S3 download.
 * Presigned URL is short-lived (15 minutes) for security.
 * </p>
 *
 * @param downloadUrl Presigned S3 URL (15-minute expiry)
 * @param fileName    Suggested filename for browser download
 * @param fileSize    File size in bytes
 * @param expiresAt   URL expiration timestamp
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchHistoryController
 */
@Schema(description = "File download response with presigned URL")
public record FileDownloadResponseDto(
        @Schema(description = "Presigned S3 URL for download", example = "https://s3.amazonaws.com/bucket/key?X-Amz-Signature=...")
        String downloadUrl,

        @Schema(description = "Suggested filename for browser download", example = "sales-2024.csv.gz")
        String fileName,

        @Schema(description = "File size in bytes", example = "2621440")
        Long fileSize,

        @Schema(description = "URL expiration timestamp (ISO-8601)", example = "2025-01-15T10:50:00Z")
        Instant expiresAt
) {
    /**
     * Create DTO with presigned URL and metadata.
     *
     * @param downloadUrl Presigned URL
     * @param fileName    Original filename
     * @param fileSize    File size in bytes
     * @param expiresAt   Expiration timestamp
     * @return FileDownloadResponseDto
     */
    public static FileDownloadResponseDto of(String downloadUrl, String fileName, Long fileSize, Instant expiresAt) {
        return new FileDownloadResponseDto(downloadUrl, fileName, fileSize, expiresAt);
    }
}
