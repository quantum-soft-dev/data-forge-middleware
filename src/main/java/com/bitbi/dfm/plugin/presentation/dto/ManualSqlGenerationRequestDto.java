package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO for manual SQL generation.
 *
 * <p>Used by admin endpoint to manually trigger SQL generation for a specific batch.</p>
 */
@Schema(description = "Request to manually generate SQL for a batch")
public record ManualSqlGenerationRequestDto(
        @Schema(
                description = "The batch ID to generate SQL for",
                example = "5b068853-dfa5-44b1-b28e-ad98213118f5",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "batchId is required")
        UUID batchId,

        @Schema(
                description = "If true, generates all INSERTs (full init). If false, generates diff with previous batch.",
                example = "false",
                defaultValue = "false"
        )
        boolean forceFullGeneration
) {
    public ManualSqlGenerationRequestDto {
        // Default forceFullGeneration to false if not specified
        if (forceFullGeneration == false) {
            forceFullGeneration = false;
        }
    }
}
