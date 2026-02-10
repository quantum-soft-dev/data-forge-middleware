package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.application.SiteService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for site creation (Auth V2).
 * <p>
 * Auth V2: No clientSecret or siteIdentifier returned.
 * Authentication is handled via Device Flow (JWT + refresh token).
 * </p>
 *
 * @param id             Site unique identifier
 * @param accountId      Parent account identifier
 * @param siteName       Site name (unique within account)
 * @param name           Site display name
 * @param isActive       Whether site is active
 * @param retentionDays  Retention period in days for batch cleanup
 * @param createdAt      Site creation timestamp
 * @author Data Forge Team
 * @version 2.0.0
 * @see com.bitbi.dfm.site.presentation.SiteAdminController
 */
@Schema(description = "Site creation response")
public record SiteCreationResponseDto(
        @Schema(description = "Site unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Parent account identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID accountId,

        @Schema(description = "Site name (unique within account)", example = "example.com")
        String siteName,

        @Schema(description = "Site display name", example = "Example Website")
        String name,

        @Schema(description = "Whether site is active", example = "true")
        boolean isActive,

        @Schema(description = "Retention period in days for batch cleanup", example = "45")
        int retentionDays,

        @Schema(description = "Site creation timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant createdAt
) {
    /**
     * Create DTO from SiteCreationResult.
     *
     * @param result Site creation result from service
     * @return SiteCreationResponseDto
     */
    public static SiteCreationResponseDto fromCreationResult(SiteService.SiteCreationResult result) {
        return new SiteCreationResponseDto(
                result.site().getId(),
                result.site().getAccountId(),
                result.site().getSiteName(),
                result.site().getDisplayName(),
                result.site().getIsActive(),
                result.site().getRetentionDays(),
                result.site().getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
