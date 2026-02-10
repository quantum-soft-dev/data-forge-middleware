package com.bitbi.dfm.error.presentation.dto;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorSeverity;
import com.bitbi.dfm.site.domain.Site;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Full response DTO for global error details.
 * <p>
 * Includes all fields for error detail view.
 * </p>
 *
 * @param id            Error log identifier
 * @param siteId        Site identifier
 * @param siteName      Site domain name
 * @param type          Error type/category
 * @param title         Error title
 * @param message       Full error message
 * @param stackTrace    Stack trace (nullable)
 * @param clientVersion Client version (nullable)
 * @param metadata      Additional metadata (nullable)
 * @param severity      Error severity level
 * @param isRead        Whether the error has been marked as read
 * @param occurredAt    Error occurrence timestamp
 */
@Schema(description = "Full details of a global error")
public record GlobalErrorResponseDto(
        @Schema(description = "Unique error log identifier")
        UUID id,

        @Schema(description = "Site identifier")
        UUID siteId,

        @Schema(description = "Site domain name")
        String siteName,

        @Schema(description = "Error type/category")
        String type,

        @Schema(description = "Error title")
        String title,

        @Schema(description = "Full error message")
        String message,

        @Schema(description = "Stack trace (nullable)")
        String stackTrace,

        @Schema(description = "Client version (nullable)")
        String clientVersion,

        @Schema(description = "Additional metadata (nullable)")
        Map<String, Object> metadata,

        @Schema(description = "Error severity level", allowableValues = {"CRITICAL", "ERROR", "WARNING", "INFO"})
        ErrorSeverity severity,

        @Schema(description = "Whether the error has been marked as read")
        boolean isRead,

        @Schema(description = "Error occurrence timestamp")
        Instant occurredAt
) {
    /**
     * Create full DTO from ErrorLog entity and Site.
     *
     * @param errorLog the error log entity
     * @param site     the site entity (for site name)
     * @return full response DTO
     */
    public static GlobalErrorResponseDto fromEntity(ErrorLog errorLog, Site site) {
        return new GlobalErrorResponseDto(
                errorLog.getId(),
                errorLog.getSiteId(),
                site != null ? site.getSiteName() : "Unknown",
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
