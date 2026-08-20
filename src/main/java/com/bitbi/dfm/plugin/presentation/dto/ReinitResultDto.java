package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * DTO for plugin reinit operation result.
 * Contains details about what was deleted and the batch set as the new baseline.
 *
 * <p>Feature 015: Plugin Reinit Option. The async SQL regeneration that feature originally
 * performed was removed — reinit now only re-baselines — so {@code sqlGenerationTriggered} is
 * always {@code false} and survives purely for API compatibility.</p>
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

        @Schema(description = "Always false: reinit no longer triggers SQL generation "
                + "(field kept for API compatibility)")
        boolean sqlGenerationTriggered,

        @Schema(description = "ID of the batch set as the new baseline (null if no completed batches)")
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
     * @param sqlGenerationTriggered always {@code false} — reinit no longer triggers SQL
     *                                generation (kept for API compatibility)
     * @param batchId the batch set as the new baseline (null if no completed batches)
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
        String message;
        if (batchId != null) {
            // New baseline batch logic: client should download CSV files
            message = "Plugin reinitialized. New baseline batch set. " +
                    "Client should download CSV files via /sites/{siteId}/files endpoint.";
        } else {
            // No batches exist yet
            message = "Plugin reinitialized. No completed batches found. " +
                    "First future batch will become baseline.";
        }

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
