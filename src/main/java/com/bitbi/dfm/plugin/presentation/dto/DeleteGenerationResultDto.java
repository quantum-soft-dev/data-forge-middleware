package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * DTO for SQL generation deletion result.
 */
@Schema(description = "Result of deleting a SQL generation")
public record DeleteGenerationResultDto(
        @Schema(description = "ID of the deleted generation")
        UUID generationId,

        @Schema(description = "ID of the batch the generation was for")
        UUID batchId,

        @Schema(description = "ID of the site the generation was for")
        UUID siteId,

        @Schema(description = "Size of deleted file in bytes")
        long deletedBytes,

        @Schema(description = "Whether the S3 file was successfully deleted")
        boolean s3FileDeleted,

        @Schema(description = "Result message")
        String message
) {
}
