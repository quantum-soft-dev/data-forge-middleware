package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an existing site.
 * <p>
 * Replaces Map<String, Object> input for site update endpoint.
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * </p>
 *
 * @param displayName New site display name (2-100 characters, required)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.site.presentation.SiteAdminController
 */
@Schema(description = "Request body for updating an existing site")
public record UpdateSiteRequestDto(

        @Schema(
                description = "New site display name",
                example = "Updated Website Name",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 100, message = "Display name must be 2-100 characters")
        String displayName
) {
}
