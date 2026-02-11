package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.application.SiteService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for user site creation (Auth V2).
 * <p>
 * Returns site details with the plaintext password.
 * Auth V2: No siteIdentifier (composite domain) returned.
 * Authentication is handled via Device Flow (JWT + refresh token).
 * </p>
 *
 * @param site     Site details
 * @param password Plaintext password (ONLY shown at creation)
 * @author Data Forge Team
 * @version 2.0.0
 */
@Schema(description = "User site creation response with site details and password")
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
