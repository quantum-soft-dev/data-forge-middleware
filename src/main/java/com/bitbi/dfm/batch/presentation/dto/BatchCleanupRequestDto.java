package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Batch cleanup request (admin)")
public record BatchCleanupRequestDto(
        @Schema(description = "Optional site ID to scope cleanup", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID siteId,

        @Schema(description = "Optional account ID to scope cleanup", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID accountId,

        @Schema(description = "Retention period override in days", example = "45")
        @Min(1)
        @Max(3650)
        Integer retentionDays,

        @Schema(description = "Override cutoff (delete batches started before this timestamp)", example = "2025-01-15T10:30:00")
        LocalDateTime olderThan,

        @Schema(description = "Maximum number of batches to process", example = "1000")
        @Min(1)
        @Max(10000)
        Integer limit,

        @Schema(description = "Dry run only (no deletions)", example = "true")
        Boolean dryRun
) {
}
