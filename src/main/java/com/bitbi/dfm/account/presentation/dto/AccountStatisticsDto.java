package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for account statistics.
 * <p>
 * Contains aggregated statistics about sites, batches, and files
 * for a specific account.
 * </p>
 *
 * @param accountId         Account unique identifier
 * @param sitesCount        Total number of sites
 * @param activeSites       Number of active sites
 * @param totalBatches      Total number of batches
 * @param completedBatches  Number of completed batches
 * @param failedBatches     Number of failed batches
 * @param totalFiles        Total number of uploaded files
 * @param totalStorageSize  Total storage size in bytes
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.account.presentation.AccountAdminController
 */
@Schema(description = "Account statistics")
public record AccountStatisticsDto(
        @Schema(description = "Account unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID accountId,

        @Schema(description = "Total number of sites", example = "5")
        int sitesCount,

        @Schema(description = "Number of active sites", example = "4")
        int activeSites,

        @Schema(description = "Total number of batches", example = "120")
        int totalBatches,

        @Schema(description = "Number of completed batches", example = "110")
        int completedBatches,

        @Schema(description = "Number of failed batches", example = "5")
        int failedBatches,

        @Schema(description = "Total number of uploaded files", example = "1500")
        int totalFiles,

        @Schema(description = "Total storage size in bytes", example = "52428800")
        long totalStorageSize
) {
    /**
     * Create DTO from statistics map.
     *
     * @param statistics Statistics map from AccountStatisticsService
     * @return AccountStatisticsDto
     */
    public static AccountStatisticsDto fromMap(Map<String, Object> statistics) {
        return new AccountStatisticsDto(
                (UUID) statistics.get("accountId"),
                ((Number) statistics.getOrDefault("totalSites", 0)).intValue(),
                ((Number) statistics.getOrDefault("activeSites", 0)).intValue(),
                ((Number) statistics.getOrDefault("totalBatches", 0)).intValue(),
                0, // completedBatches - TODO: Add to service
                0, // failedBatches - TODO: Add to service
                ((Number) statistics.getOrDefault("totalFiles", 0)).intValue(),
                0L // totalStorageSize - TODO: Add to service
        );
    }
}
