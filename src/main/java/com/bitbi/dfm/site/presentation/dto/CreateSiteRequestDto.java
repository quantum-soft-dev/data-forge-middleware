package com.bitbi.dfm.site.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new site.
 * <p>
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * Site name is case-insensitive (accepts uppercase but stored as lowercase).
 * Unicode characters are allowed in site names.
 * </p>
 *
 * @param siteName    Site name (unique within account, case-insensitive, 1-255 characters)
 * @param displayName Site display name (2-100 characters, required)
 * @param password    Site password (optional, will be generated if not provided)
 * @author Data Forge Team
 * @version 2.0.0
 * @see com.bitbi.dfm.site.presentation.SiteAdminController
 */
@Schema(description = "Request body for creating a new site")
public record CreateSiteRequestDto(

        @Schema(
                description = "Site name (unique within account, case-insensitive, unicode allowed)",
                example = "example.com",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Site name is required")
        @Size(min = 1, max = 255, message = "Site name must be 1-255 characters")
        String siteName,

        @Schema(
                description = "Site display name",
                example = "Example Website",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Display name is required")
        @Size(min = 2, max = 100, message = "Display name must be 2-100 characters")
        String displayName,

        @Schema(
                description = "Site password (optional, will be generated if not provided). Generated passwords are 8-12 characters. Manual passwords must be at least 8 characters (alphanumeric only).",
                example = "myPass123",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(min = 8, message = "Password must be at least 8 characters if provided")
        String password
) {
}
