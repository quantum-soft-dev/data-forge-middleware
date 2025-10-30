package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new site.
 * <p>
 * Replaces Map<String, Object> input for site creation endpoint.
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * </p>
 *
 * @param domain      Site domain (unique within account, lowercase, 3-255 characters)
 * @param displayName Site display name (2-100 characters, required)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.site.presentation.SiteAdminController
 */
@Schema(description = "Request body for creating a new site")
public record CreateSiteRequestDto(

        @Schema(
                description = "Site domain (unique within account, lowercase)",
                example = "example.com",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Domain is required")
        @Size(min = 3, max = 255, message = "Domain must be 3-255 characters")
        @Pattern(regexp = "^[a-z0-9.-]+$", message = "Domain must contain only lowercase letters, numbers, dots, and hyphens")
        String domain,

        @Schema(
                description = "Site display name",
                example = "Example Website",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 100, message = "Display name must be 2-100 characters")
        String displayName
) {
}
