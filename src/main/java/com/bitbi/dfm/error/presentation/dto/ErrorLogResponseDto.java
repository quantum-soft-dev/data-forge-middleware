package com.bitbi.dfm.error.presentation.dto;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorSeverity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for error log entries.
 * <p>
 * Provides immutable representation of error log data for API responses.
 * Includes JSONB metadata as Map for flexible error context.
 * </p>
 *
 * FR-001: Structured response objects
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation
 *
 * @param id            Unique error log identifier
 * @param siteId        Site that logged this error
 * @param batchId       Batch this error is associated with (nullable)
 * @param type          Error type/category
 * @param title         Error title
 * @param message       Error message
 * @param stackTrace    Stack trace (nullable)
 * @param clientVersion Client version that generated the error (nullable)
 * @param metadata      Additional JSONB metadata (nullable)
 * @param severity      Error severity level (CRITICAL, ERROR, WARNING, INFO)
 * @param isRead        Whether the error has been marked as read
 * @param occurredAt    Error occurrence timestamp
 */
@Schema(description = "Error log entry response")
public record ErrorLogResponseDto(
        @Schema(description = "Unique error log identifier")
        UUID id,

        @Schema(description = "Site that logged this error")
        UUID siteId,

        @Schema(description = "Batch this error is associated with (null for global errors)")
        UUID batchId,

        @Schema(description = "Error type/category")
        String type,

        @Schema(description = "Error title")
        String title,

        @Schema(description = "Error message")
        String message,

        @Schema(description = "Stack trace (nullable)")
        String stackTrace,

        @Schema(description = "Client version that generated the error (nullable)")
        String clientVersion,

        @Schema(description = "Additional JSONB metadata (nullable)")
        Map<String, Object> metadata,

        @Schema(description = "Error severity level", allowableValues = {"CRITICAL", "ERROR", "WARNING", "INFO"})
        ErrorSeverity severity,

        @Schema(description = "Whether the error has been marked as read")
        boolean isRead,

        @Schema(description = "Error occurrence timestamp")
        Instant occurredAt
) {

    /**
     * Convert ErrorLog domain entity to ErrorLogResponseDto.
     * <p>
     * Maps all fields from entity to DTO, converting:
     * - LocalDateTime timestamp to Instant (UTC)
     * - JSONB metadata preserved as Map
     * </p>
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
                errorLog.getSeverity(),
                errorLog.isRead(),
                errorLog.getOccurredAt().toInstant(ZoneOffset.UTC)
        );
    }
}
