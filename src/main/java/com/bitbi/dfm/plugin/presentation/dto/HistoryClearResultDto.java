package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for history clear operation result.
 * Contains details about what was deleted and any failures.
 */
@Schema(description = "Result of clearing plugin history")
public record HistoryClearResultDto(
        @Schema(description = "Number of SQL generation records deleted")
        long deletedGenerations,

        @Schema(description = "Number of S3 files deleted")
        long deletedFilesCount,

        @Schema(description = "Total size of deleted files in bytes")
        long deletedTotalBytes,

        @Schema(description = "S3 keys that failed to delete (empty if all succeeded)")
        List<String> failedS3Keys,

        @Schema(description = "Whether the plugin was deactivated")
        boolean pluginDeactivated,

        @Schema(description = "When the clear operation completed")
        LocalDateTime clearedAt
) {
    /**
     * Creates a result DTO for a successful clear operation.
     *
     * @param deletedGenerations number of generations deleted
     * @param deletedFilesCount number of S3 files deleted
     * @param deletedTotalBytes total bytes deleted
     * @param failedS3Keys list of S3 keys that failed to delete
     * @return the DTO
     */
    public static HistoryClearResultDto create(
            long deletedGenerations,
            long deletedFilesCount,
            long deletedTotalBytes,
            List<String> failedS3Keys
    ) {
        return new HistoryClearResultDto(
                deletedGenerations,
                deletedFilesCount,
                deletedTotalBytes,
                failedS3Keys != null ? failedS3Keys : List.of(),
                true, // plugin is always deactivated
                LocalDateTime.now()
        );
    }
}
