package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.application.SiteService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for user site creation.
 * <p>
 * Returns site details in a nested structure with the plaintext password
 * and site identifier for Basic Auth (FR-019).
 * This is specifically for user-facing endpoints where the password
 * is displayed only once at creation time.
 * </p>
 *
 * @param site           Site details (domain is display format)
 * @param password       Plaintext password (ONLY shown at creation)
 * @param siteIdentifier Full identifier for Basic Auth (accountId_domain)
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "User site creation response with site details, password, and auth identifier")
public record UserSiteCreationResponseDto(
        @Schema(description = "Created site details")
        SiteResponseDto site,

        @Schema(description = "Plaintext password (only shown at creation)", example = "Pass1234")
        String password,

        @Schema(description = "Site identifier for Basic Auth (accountId_domain)",
                example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890_example.com")
        String siteIdentifier
) {
    /**
     * Create DTO from SiteCreationResult.
     *
     * @param result Site creation result from service
     * @return UserSiteCreationResponseDto with site identifier
     */
    public static UserSiteCreationResponseDto fromCreationResult(SiteService.SiteCreationResult result) {
        return new UserSiteCreationResponseDto(
                SiteResponseDto.fromEntity(result.site()),
                result.plaintextSecret(),
                result.site().getDomain() // Full composite domain for auth
        );
    }
}
