package com.bitbi.dfm.clientlog.presentation.dto;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;

import java.time.Instant;

/**
 * Response DTO for client log download with presigned URL.
 *
 * Feature: 021-unified-upload-api
 * User Story: US5 - Admin Views and Downloads Client Logs
 */
public record ClientLogDownloadResponseDto(
        String url,
        Instant expiresAt,
        String filename,
        long fileSize
) {
    public static ClientLogDownloadResponseDto fromPresignedUrl(
            S3PresignedUrlService.PresignedUrlResult presignedUrl,
            ClientDiagnosticLog log
    ) {
        return new ClientLogDownloadResponseDto(
                presignedUrl.url(),
                presignedUrl.expiresAt(),
                log.getFilename(),
                log.getFileSize()
        );
    }
}
