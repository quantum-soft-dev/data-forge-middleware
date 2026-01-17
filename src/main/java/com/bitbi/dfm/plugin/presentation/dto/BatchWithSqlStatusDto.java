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
        UUID generationId,

        @Schema(description = "Number of INSERT statements (null if no SQL)")
        Integer insertCount,

        @Schema(description = "Number of UPDATE statements (null if no SQL)")
        Integer updateCount,

        @Schema(description = "Number of DELETE statements (null if no SQL)")
        Integer deleteCount,

        @Schema(description = "When SQL was generated (null if no SQL)")
        Instant generatedAt,

        @Schema(description = "Whether no changes were detected when generation was attempted")
        boolean noChangesDetected
) {
    /**
     * Creates a DTO from batch data with SQL status and statistics.
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
            UUID generationId,
            Integer insertCount,
            Integer updateCount,
            Integer deleteCount,
            Instant generatedAt,
            boolean noChangesDetected) {
        return new BatchWithSqlStatusDto(
                batchId, siteId, siteDomain, status, completedAt,
                fileCount, totalSizeBytes, isBaseline, hasSql, generationId,
                insertCount, updateCount, deleteCount, generatedAt, noChangesDetected);
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
            boolean isBaseline,
            boolean noChangesDetected) {
        return new BatchWithSqlStatusDto(
                batchId, siteId, siteDomain, status, completedAt,
                fileCount, totalSizeBytes, isBaseline, false, null,
                null, null, null, null, noChangesDetected);
    }

    /**
     * Creates a DTO for a batch with SQL and statistics.
     */
    public static BatchWithSqlStatusDto withSql(
            UUID batchId,
            UUID siteId,
            String siteDomain,
            String status,
            Instant completedAt,
            int fileCount,
            long totalSizeBytes,
            UUID generationId,
            int insertCount,
            int updateCount,
            int deleteCount,
            Instant generatedAt) {
        return new BatchWithSqlStatusDto(
                batchId, siteId, siteDomain, status, completedAt,
                fileCount, totalSizeBytes, false, true, generationId,
                insertCount, updateCount, deleteCount, generatedAt, false);
    }
}
