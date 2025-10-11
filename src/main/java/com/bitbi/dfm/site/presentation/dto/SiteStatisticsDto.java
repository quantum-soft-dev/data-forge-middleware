package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for site statistics.
 * <p>
 * Contains aggregated statistics about batches and files for a specific site.
 * </p>
 *
 * @param siteId            Site unique identifier
 * @param totalBatches      Total number of batches for this site
 * @param completedBatches  Number of completed batches
 * @param failedBatches     Number of failed batches
 * @param totalFiles        Total number of uploaded files
 * @param totalStorageSize  Total storage size in bytes
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.site.presentation.SiteAdminController
 */
@Schema(description = "Site statistics")
public record SiteStatisticsDto(
        @Schema(description = "Site unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID siteId,

        @Schema(description = "Total number of batches for this site", example = "50")
        int totalBatches,

        @Schema(description = "Number of completed batches", example = "45")
        int completedBatches,

        @Schema(description = "Number of failed batches", example = "2")
        int failedBatches,

        @Schema(description = "Total number of uploaded files", example = "600")
        int totalFiles,

        @Schema(description = "Total storage size in bytes", example = "20971520")
        long totalStorageSize
) {
    /**
     * Create DTO from statistics map.
     *
     * @param statistics Statistics map from AccountStatisticsService
     * @return SiteStatisticsDto
     */
    public static SiteStatisticsDto fromMap(Map<String, Object> statistics) {
        return new SiteStatisticsDto(
                (UUID) statistics.get("siteId"),
                ((Number) statistics.getOrDefault("totalBatches", 0)).intValue(),
                0, // completedBatches - TODO: Add to service
                0, // failedBatches - TODO: Add to service
                ((Number) statistics.getOrDefault("totalFiles", 0)).intValue(),
                ((Number) statistics.getOrDefault("totalStorageSize", 0L)).longValue()
        );
    }
}
