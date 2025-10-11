package com.bitbi.dfm.error.presentation.dto;

import com.bitbi.dfm.error.domain.ErrorLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for error log lists (Admin UI API).
 * <p>
 * Contains essential error information for paginated error log endpoints.
 * </p>
 *
 * @param id            Error log unique identifier
 * @param batchId       Batch identifier (may be null)
 * @param siteId        Site identifier
 * @param type          Error type (e.g., "VALIDATION_ERROR", "UPLOAD_ERROR")
 * @param title         Error title
 * @param message       Error message
 * @param stackTrace    Stack trace (may be null)
 * @param clientVersion Client version string (may be null)
 * @param metadata      Additional metadata as JSONB
 * @param occurredAt    Error occurrence timestamp
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.error.presentation.ErrorAdminController
 */
@Schema(description = "Error log summary for list view")
public record ErrorLogSummaryDto(
        @Schema(description = "Error log unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Batch identifier (may be null)", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID batchId,

        @Schema(description = "Site identifier", example = "456e7890-a12b-34c5-d678-901234567890")
        UUID siteId,

        @Schema(description = "Error type", example = "UPLOAD_ERROR")
        String type,

        @Schema(description = "Error title", example = "File upload failed")
        String title,

        @Schema(description = "Error message", example = "Failed to upload file data.csv: network timeout")
        String message,

        @Schema(description = "Stack trace (may be null)", example = "java.net.SocketTimeoutException: timeout\n  at ...")
        String stackTrace,

        @Schema(description = "Client version string (may be null)", example = "1.2.3")
        String clientVersion,

        @Schema(description = "Additional metadata as JSON", example = "{\"filename\": \"data.csv\", \"size\": 1048576}")
        Map<String, Object> metadata,

        @Schema(description = "Error occurrence timestamp (ISO-8601)", example = "2025-01-15T10:35:00Z")
        Instant occurredAt
) {
    /**
     * Create DTO from ErrorLog entity.
     *
     * @param error ErrorLog entity
     * @return ErrorLogSummaryDto
     */
    public static ErrorLogSummaryDto fromEntity(ErrorLog error) {
        return new ErrorLogSummaryDto(
                error.getId(),
                error.getBatchId(),
                error.getSiteId(),
                error.getType(),
                error.getTitle(),
                error.getMessage(),
                error.getStackTrace(),
                error.getClientVersion(),
                error.getMetadata(),
                error.getOccurredAt().toInstant(ZoneOffset.UTC)
        );
    }
}
