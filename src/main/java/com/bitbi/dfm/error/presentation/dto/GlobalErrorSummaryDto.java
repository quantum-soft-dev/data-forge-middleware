package com.bitbi.dfm.error.presentation.dto;

import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorSeverity;
import com.bitbi.dfm.site.domain.Site;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Summary DTO for global error list items.
 * <p>
 * Contains essential fields for list display, excludes message and stackTrace for performance.
 * </p>
 *
 * @param id         Error log identifier
 * @param siteId     Site identifier
 * @param siteName   Site domain name
 * @param type       Error type/category
 * @param title      Error title
 * @param severity   Error severity level
 * @param isRead     Whether the error has been marked as read
 * @param occurredAt Error occurrence timestamp
 */
@Schema(description = "Summary of a global error for list display")
public record GlobalErrorSummaryDto(
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

        @Schema(description = "Error severity level", allowableValues = {"CRITICAL", "ERROR", "WARNING", "INFO"})
        ErrorSeverity severity,

        @Schema(description = "Whether the error has been marked as read")
        boolean isRead,

        @Schema(description = "Error occurrence timestamp")
        Instant occurredAt
) {
    /**
     * Create summary DTO from ErrorLog entity and Site.
     *
     * @param errorLog the error log entity
     * @param site     the site entity (for site name)
     * @return summary DTO
     */
    public static GlobalErrorSummaryDto fromEntity(ErrorLog errorLog, Site site) {
        return new GlobalErrorSummaryDto(
                errorLog.getId(),
                errorLog.getSiteId(),
                site != null ? site.getSiteName() : "Unknown",
                errorLog.getType(),
                errorLog.getTitle(),
                errorLog.getSeverity(),
                errorLog.isRead(),
                errorLog.getOccurredAt().toInstant(ZoneOffset.UTC)
        );
    }

    /**
     * Create summary DTO from ErrorLog entity with site name.
     *
     * @param errorLog the error log entity
     * @param siteName the site domain name
     * @return summary DTO
     */
    public static GlobalErrorSummaryDto fromEntity(ErrorLog errorLog, String siteName) {
        return new GlobalErrorSummaryDto(
                errorLog.getId(),
                errorLog.getSiteId(),
                siteName != null ? siteName : "Unknown",
                errorLog.getType(),
                errorLog.getTitle(),
                errorLog.getSeverity(),
                errorLog.isRead(),
                errorLog.getOccurredAt().toInstant(ZoneOffset.UTC)
        );
    }
}
