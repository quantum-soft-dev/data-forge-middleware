package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * DTO for plugin reinit operation result.
 * Contains details about what was deleted and whether SQL generation was triggered.
 *
 * <p>Feature 015: Plugin Reinit Option</p>
 */
@Schema(description = "Result of reinitializing plugin SQL state")
public record ReinitResultDto(
        @Schema(description = "Whether the reinit operation completed successfully")
        boolean success,

        @Schema(description = "Number of SQL generation records deleted")
        long deletedGenerations,

        @Schema(description = "Number of S3 files deleted")
        long deletedS3Files,

        @Schema(description = "Total storage freed in bytes")
        long totalBytesFreed,

        @Schema(description = "Whether SQL generation was triggered (false if no batches exist)")
        boolean sqlGenerationTriggered,

        @Schema(description = "ID of the batch used for SQL generation (null if no batches)")
        UUID batchId,

        @Schema(description = "Human-readable status message")
        String message,

        @Schema(description = "Warnings for any S3 files that failed to delete")
        List<String> s3DeleteWarnings
) {
    /**
     * Creates a result DTO for a successful reinit operation.
     *
     * @param deletedGenerations number of generations deleted
     * @param deletedS3Files number of S3 files deleted
     * @param totalBytesFreed total bytes deleted
     * @param sqlGenerationTriggered whether SQL generation was triggered
     * @param batchId the batch ID used for regeneration (null if no batch)
     * @param failedS3Keys list of S3 keys that failed to delete
     * @return the DTO
     */
    public static ReinitResultDto success(
            long deletedGenerations,
            long deletedS3Files,
            long totalBytesFreed,
            boolean sqlGenerationTriggered,
            UUID batchId,
            List<String> failedS3Keys
    ) {
        String message = sqlGenerationTriggered
                ? "Plugin reinitialized. SQL generation running asynchronously."
                : "Plugin reinitialized. No completed batches found for SQL generation.";

        return new ReinitResultDto(
                true,
                deletedGenerations,
                deletedS3Files,
                totalBytesFreed,
                sqlGenerationTriggered,
                batchId,
                message,
                failedS3Keys != null ? failedS3Keys : List.of()
        );
    }
}
