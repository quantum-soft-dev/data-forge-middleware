package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Update batch retention cleanup scheduler settings")
public record UpdateBatchRetentionScheduleRequestDto(
        @NotBlank
        @Schema(description = "New cron expression (Spring format: sec min hour day month day-of-week)", example = "0 30 3 * * *")
        String cron
) {
}

