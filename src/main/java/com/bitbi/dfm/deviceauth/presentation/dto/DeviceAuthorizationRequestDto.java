package com.bitbi.dfm.deviceauth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for initiating device authorization.
 *
 * @param siteName        requested site name (alphanumeric + hyphens)
 * @param siteDescription optional site description
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Request to initiate device authorization")
public record DeviceAuthorizationRequestDto(

        @Schema(
                description = "Site name (alphanumeric and hyphens only)",
                example = "warehouse-01",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Site name is required")
        @Size(max = 100, message = "Site name must be at most 100 characters")
        @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9]$|^[a-zA-Z0-9]$",
                message = "Site name must contain only alphanumeric characters and hyphens, cannot start or end with hyphen"
        )
        String siteName,

        @Schema(
                description = "Optional site description",
                example = "Main warehouse terminal",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(max = 500, message = "Site description must be at most 500 characters")
        String siteDescription
) {
}
