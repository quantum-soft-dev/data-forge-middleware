package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for SQL regeneration result.
 * Contains information about the original and new generation.
 */
@Schema(description = "Result of regenerating SQL for a batch")
public record RegenerateResultDto(
        @Schema(description = "ID of the original generation that was superseded")
        UUID originalGenerationId,

        @Schema(description = "ID of the new generation created")
        UUID newGenerationId,

        @Schema(description = "Total number of SQL statements in new generation")
        int statementCount,

        @Schema(description = "Number of INSERT statements")
        int insertCount,

        @Schema(description = "Number of UPDATE statements")
        int updateCount,

        @Schema(description = "Number of DELETE statements")
        int deleteCount,

        @Schema(description = "Time taken to regenerate SQL in milliseconds")
        long generationDurationMs,

        @Schema(description = "When the regeneration completed")
        LocalDateTime regeneratedAt
) {
    /**
     * Creates a result DTO from the new generation entity.
     *
     * @param originalId the ID of the original generation that was superseded
     * @param newGeneration the new generation entity
     * @return the DTO
     */
    public static RegenerateResultDto fromEntity(UUID originalId, PluginSqlGeneration newGeneration) {
        return new RegenerateResultDto(
                originalId,
                newGeneration.getId(),
                newGeneration.getStatementCount(),
                newGeneration.getInsertCount(),
                newGeneration.getUpdateCount(),
                newGeneration.getDeleteCount(),
                newGeneration.getGenerationDurationMs(),
                newGeneration.getCreatedAt()
        );
    }
}
