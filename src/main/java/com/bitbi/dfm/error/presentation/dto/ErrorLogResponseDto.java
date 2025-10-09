package com.bitbi.dfm.error.presentation.dto;

import com.bitbi.dfm.error.domain.ErrorLog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for error log entries.
 *
 * Provides immutable representation of error log data for API responses.
 * Includes JSONB metadata as Map for flexible error context.
 *
 * FR-001: Structured response objects
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation
 *
 * @param id Unique error log identifier
 * @param siteId Site that logged this error
 * @param batchId Batch this error is associated with (nullable)
 * @param type Error type/category
 * @param title Error title
 * @param message Error message
 * @param stackTrace Stack trace (nullable)
 * @param clientVersion Client version that generated the error (nullable)
 * @param metadata Additional JSONB metadata (nullable)
 * @param occurredAt Error occurrence timestamp
 */
public record ErrorLogResponseDto(
    UUID id,
    UUID siteId,
    UUID batchId,
    String type,
    String title,
    String message,
    String stackTrace,
    String clientVersion,
    Map<String, Object> metadata,
    Instant occurredAt
) {

    /**
     * Convert ErrorLog domain entity to ErrorLogResponseDto.
     *
     * Maps all fields from entity to DTO, converting:
     * - LocalDateTime timestamp to Instant (UTC)
     * - JSONB metadata preserved as Map
     *
     * @param errorLog The domain entity to convert
     * @return ErrorLogResponseDto with all fields mapped
     */
    public static ErrorLogResponseDto fromEntity(ErrorLog errorLog) {
        return new ErrorLogResponseDto(
            errorLog.getId(),
            errorLog.getSiteId(),
            errorLog.getBatchId(),
            errorLog.getType(),
            errorLog.getTitle(),
            errorLog.getMessage(),
            errorLog.getStackTrace(),
            errorLog.getClientVersion(),
            errorLog.getMetadata(),
            errorLog.getOccurredAt().toInstant(ZoneOffset.UTC)
        );
    }
}
