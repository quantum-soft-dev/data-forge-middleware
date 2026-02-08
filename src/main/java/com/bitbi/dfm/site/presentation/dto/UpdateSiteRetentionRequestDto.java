package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for updating site retention policy.
 *
 * @param retentionDays Retention period in days
 */
@Schema(description = "Update site retention policy request")
public record UpdateSiteRetentionRequestDto(
        @Schema(description = "Retention period in days", example = "45")
        @NotNull
        @Min(1)
        @Max(3650)
        Integer retentionDays
) {
}
