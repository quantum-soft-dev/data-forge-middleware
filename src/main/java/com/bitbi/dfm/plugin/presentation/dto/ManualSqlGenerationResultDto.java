package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for manual SQL generation result.
 */
@Schema(description = "Result of manual SQL generation")
public record ManualSqlGenerationResultDto(
        @Schema(description = "Whether SQL was generated (false if batch is baseline)")
        boolean generated,

        @Schema(description = "The generation ID (null if not generated)")
        UUID generationId,

        @Schema(description = "The batch ID that was processed")
        UUID batchId,

        @Schema(description = "The site ID")
        UUID siteId,

        @Schema(description = "S3 key of the generated SQL file (null if not generated)")
        String s3Key,

        @Schema(description = "Number of INSERT statements")
        int insertCount,

        @Schema(description = "Number of UPDATE statements")
        int updateCount,

        @Schema(description = "Number of DELETE statements")
        int deleteCount,

        @Schema(description = "Total SQL statements")
        int statementCount,

        @Schema(description = "Number of CSV files processed")
        int filesProcessed,

        @Schema(description = "Whether full generation was used (all INSERTs)")
        boolean fullGeneration,

        @Schema(description = "Message describing the result")
        String message,

        @Schema(description = "Generation timestamp")
        LocalDateTime createdAt
) {
    /**
     * Creates a result from a successful SQL generation.
     */
    public static ManualSqlGenerationResultDto fromGeneration(PluginSqlGeneration generation, boolean fullGeneration) {
        return new ManualSqlGenerationResultDto(
                true,
                generation.getId(),
                generation.getSourceBatchId(),
                generation.getSiteId(),
                generation.getS3Key(),
                generation.getInsertCount(),
                generation.getUpdateCount(),
                generation.getDeleteCount(),
                generation.getStatementCount(),
                generation.getFilesProcessed(),
                fullGeneration,
                "SQL generation completed successfully",
                generation.getCreatedAt()
        );
    }

    /**
     * Creates a result when no SQL was generated (baseline batch).
     */
    public static ManualSqlGenerationResultDto skipped(UUID batchId, UUID siteId, String reason) {
        return new ManualSqlGenerationResultDto(
                false,
                null,
                batchId,
                siteId,
                null,
                0,
                0,
                0,
                0,
                0,
                false,
                reason,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }
}
