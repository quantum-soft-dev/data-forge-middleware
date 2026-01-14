package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing a batch with its SQL generation status.
 * Used for displaying batch list with SQL status indicators.
 */
@Schema(description = "Batch with SQL generation status")
public record BatchWithSqlStatusDto(
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
        boolean isBaseline,

        @Schema(description = "Whether SQL has been generated for this batch")
        boolean hasSql,

        @Schema(description = "SQL generation ID if SQL exists")
        UUID generationId
) {
    /**
     * Creates a DTO from batch data with SQL status.
     */
    public static BatchWithSqlStatusDto of(
            UUID batchId,
            UUID siteId,
            String siteDomain,
            String status,
            Instant completedAt,
            int fileCount,
            long totalSizeBytes,
            boolean isBaseline,
            boolean hasSql,
            UUID generationId) {
        return new BatchWithSqlStatusDto(
                batchId, siteId, siteDomain, status, completedAt,
                fileCount, totalSizeBytes, isBaseline, hasSql, generationId);
    }

    /**
     * Creates a DTO for a batch without SQL.
     */
    public static BatchWithSqlStatusDto withoutSql(
            UUID batchId,
            UUID siteId,
            String siteDomain,
            String status,
            Instant completedAt,
            int fileCount,
            long totalSizeBytes,
            boolean isBaseline) {
        return new BatchWithSqlStatusDto(
                batchId, siteId, siteDomain, status, completedAt,
                fileCount, totalSizeBytes, isBaseline, false, null);
    }

    /**
     * Creates a DTO for a batch with SQL.
     */
    public static BatchWithSqlStatusDto withSql(
            UUID batchId,
            UUID siteId,
            String siteDomain,
            String status,
            Instant completedAt,
            int fileCount,
            long totalSizeBytes,
            UUID generationId) {
        return new BatchWithSqlStatusDto(
                batchId, siteId, siteDomain, status, completedAt,
                fileCount, totalSizeBytes, false, true, generationId);
    }
}
