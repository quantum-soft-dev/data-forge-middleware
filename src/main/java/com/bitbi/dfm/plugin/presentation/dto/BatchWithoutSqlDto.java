package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing a batch that doesn't have SQL generation.
 */
@Schema(description = "Batch without SQL generation")
public record BatchWithoutSqlDto(
        @Schema(description = "Batch ID")
        UUID batchId,

        @Schema(description = "Site ID")
        UUID siteId,

        @Schema(description = "Site domain")
        String siteDomain,

        @Schema(description = "Batch status")
        String status,

        @Schema(description = "When the batch was completed")
        Instant completedAt,

        @Schema(description = "Number of files in the batch")
        int fileCount,

        @Schema(description = "Total size of files in bytes")
        long totalSizeBytes,

        @Schema(description = "Whether this is the baseline batch (SQL shouldn't be generated)")
        boolean isBaseline
) {
}
