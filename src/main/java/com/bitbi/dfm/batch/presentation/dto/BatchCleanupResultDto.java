package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Batch cleanup result summary")
public record BatchCleanupResultDto(
        @Schema(description = "Number of batches matched by cleanup")
        int candidates,

        @Schema(description = "Number of batches deleted")
        int deletedBatches,

        @Schema(description = "Number of files deleted from S3")
        int deletedFiles,

        @Schema(description = "Total bytes deleted from S3")
        long deletedBytes,

        @Schema(description = "List of errors encountered")
        List<String> errors
) {
}
