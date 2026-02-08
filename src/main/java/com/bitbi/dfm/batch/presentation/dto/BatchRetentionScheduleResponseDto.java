package com.bitbi.dfm.batch.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Batch retention cleanup scheduler settings")
public record BatchRetentionScheduleResponseDto(
        @Schema(description = "Cron expression (Spring format: sec min hour day month day-of-week)", example = "0 0 2 * * *")
        String cron,
        @Schema(description = "Where the effective value comes from", example = "DB")
        String source,
        @Schema(description = "When it was last updated (only for DB-sourced values)")
        Instant updatedAt
) {
}

