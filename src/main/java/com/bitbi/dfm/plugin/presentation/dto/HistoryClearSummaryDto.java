package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * DTO for history clear summary shown before deletion confirmation.
 * Provides information about what will be deleted.
 */
@Schema(description = "Summary of what will be deleted when clearing history")
public record HistoryClearSummaryDto(
        @Schema(description = "Account ID whose history will be cleared")
        UUID accountId,

        @Schema(description = "Plugin identifier")
        String pluginId,

        @Schema(description = "Number of SQL generation records that will be deleted")
        long generationCount,

        @Schema(description = "Total size of SQL files that will be deleted in bytes")
        long totalFileSizeBytes,

        @Schema(description = "Whether the plugin will be deactivated as part of clearing")
        boolean pluginWillBeDeactivated,

        @Schema(description = "Warning: true if there are active batches in progress")
        boolean hasActiveBatches
) {
    /**
     * Creates a summary DTO.
     *
     * @param accountId the account ID
     * @param pluginId the plugin identifier
     * @param generationCount number of generations to delete
     * @param totalFileSizeBytes total file size in bytes
     * @param hasActiveBatches whether active batches exist
     * @return the DTO
     */
    public static HistoryClearSummaryDto create(
            UUID accountId,
            String pluginId,
            long generationCount,
            long totalFileSizeBytes,
            boolean hasActiveBatches
    ) {
        return new HistoryClearSummaryDto(
                accountId,
                pluginId,
                generationCount,
                totalFileSizeBytes,
                true, // plugin is always deactivated when clearing history
                hasActiveBatches
        );
    }
}
