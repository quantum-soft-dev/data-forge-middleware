package com.bitbi.dfm.clientlog.presentation.dto;

import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for client diagnostic log.
 * Used for both upload response (US4) and list item (US5).
 *
 * Feature: 021-unified-upload-api
 * User Story: US4, US5 - Client Diagnostic Logs
 */
public record ClientLogResponseDto(
        UUID logId,
        UUID siteId,
        String filename,
        long fileSize,
        String clientVersion,
        String os,
        LocalDateTime periodFrom,
        LocalDateTime periodTo,
        List<String> tags,
        String description,
        LocalDateTime uploadedAt,
        LocalDateTime expiresAt
) {
    public static ClientLogResponseDto fromEntity(ClientDiagnosticLog log) {
        return new ClientLogResponseDto(
                log.getId(),
                log.getSiteId(),
                log.getFilename(),
                log.getFileSize(),
                log.getClientVersion(),
                log.getOs(),
                log.getPeriodFrom(),
                log.getPeriodTo(),
                log.getTags(),
                log.getDescription(),
                log.getUploadedAt(),
                log.getExpiresAt()
        );
    }
}
