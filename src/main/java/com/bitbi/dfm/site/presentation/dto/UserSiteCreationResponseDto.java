package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.application.SiteService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for user site creation.
 * <p>
 * Returns site details in a nested structure with the plaintext password.
 * This is specifically for user-facing endpoints where the password
 * is displayed only once at creation time.
 * </p>
 *
 * @param site     Site details
 * @param password Plaintext password (ONLY shown at creation)
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "User site creation response with nested site details and plaintext password")
public record UserSiteCreationResponseDto(
        @Schema(description = "Created site details")
        SiteResponseDto site,

        @Schema(description = "Plaintext password (only shown at creation)", example = "Pass1234")
        String password
) {
    /**
     * Create DTO from SiteCreationResult.
     *
     * @param result Site creation result from service
     * @return UserSiteCreationResponseDto
     */
    public static UserSiteCreationResponseDto fromCreationResult(SiteService.SiteCreationResult result) {
        return new UserSiteCreationResponseDto(
                SiteResponseDto.fromEntity(result.site()),
                result.plaintextSecret()
        );
    }
}
