package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for SQL generation summary in list responses.
 * Contains metadata without the actual SQL content.
 */
@Schema(description = "SQL generation summary for list display")
public record SqlGenerationSummaryDto(
        @Schema(description = "Generation ID")
        UUID id,

        @Schema(description = "Source batch ID that was processed")
        UUID sourceBatchId,

        @Schema(description = "Comparison batch ID (null for first batch)")
        UUID comparisonBatchId,

        @Schema(description = "Site ID the generation belongs to")
        UUID siteId,

        @Schema(description = "Site domain name")
        String siteDomain,

        @Schema(description = "When the SQL was generated")
        LocalDateTime createdAt,

        @Schema(description = "Total number of SQL statements")
        int statementCount,

        @Schema(description = "Number of INSERT statements")
        int insertCount,

        @Schema(description = "Number of UPDATE statements")
        int updateCount,

        @Schema(description = "Number of DELETE statements")
        int deleteCount,

        @Schema(description = "Size of the SQL file in bytes")
        long fileSizeBytes,

        @Schema(description = "Time taken to generate SQL in milliseconds")
        long generationDurationMs,

        @Schema(description = "True if this was the first batch (initial load)")
        boolean isInitialLoad,

        @Schema(description = "True if this generation has been superseded by regeneration")
        boolean superseded,

        @Schema(description = "ID of the generation that superseded this one")
        UUID supersededBy
) {
    /**
     * Creates a DTO from a PluginSqlGeneration entity.
     *
     * @param entity the SQL generation entity
     * @param siteDomain the domain name of the site
     * @return the DTO
     */
    public static SqlGenerationSummaryDto fromEntity(PluginSqlGeneration entity, String siteDomain) {
        return new SqlGenerationSummaryDto(
                entity.getId(),
                entity.getSourceBatchId(),
                entity.getComparisonBatchId(),
                entity.getSiteId(),
                siteDomain,
                entity.getCreatedAt(),
                entity.getStatementCount(),
                entity.getInsertCount(),
                entity.getUpdateCount(),
                entity.getDeleteCount(),
                entity.getFileSizeBytes(),
                entity.getGenerationDurationMs(),
                entity.isFirstBatch(),
                entity.isSuperseded(),
                entity.getSupersededBy()
        );
    }
}
