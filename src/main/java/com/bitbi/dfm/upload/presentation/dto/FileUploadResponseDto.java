package com.bitbi.dfm.upload.presentation.dto;

import com.bitbi.dfm.upload.domain.UploadedFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for uploaded files.
 *
 * Provides immutable representation of uploaded file metadata for API responses.
 *
 * FR-001: Structured response objects
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation
 *
 * @param id Unique file upload identifier
 * @param batchId Batch this file belongs to
 * @param filename Original filename
 * @param s3Key S3 object key
 * @param fileSize File size in bytes
 * @param contentType MIME content type
 * @param checksum MD5 checksum
 * @param uploadedAt Upload timestamp
 */
public record FileUploadResponseDto(
    UUID id,
    UUID batchId,
    String filename,
    String s3Key,
    Long fileSize,
    String contentType,
    String checksum,
    Instant uploadedAt
) {

    /**
     * Convert UploadedFile domain entity to FileUploadResponseDto.
     *
     * Maps all fields from entity to DTO, converting:
     * - originalFileName to filename
     * - LocalDateTime timestamp to Instant (UTC)
     *
     * @param uploadedFile The domain entity to convert
     * @return FileUploadResponseDto with all fields mapped
     */
    public static FileUploadResponseDto fromEntity(UploadedFile uploadedFile) {
        return new FileUploadResponseDto(
            uploadedFile.getId(),
            uploadedFile.getBatchId(),
            uploadedFile.getOriginalFileName(),
            uploadedFile.getS3Key(),
            uploadedFile.getFileSize(),
            uploadedFile.getContentType(),
            uploadedFile.getChecksum(),
            uploadedFile.getUploadedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
