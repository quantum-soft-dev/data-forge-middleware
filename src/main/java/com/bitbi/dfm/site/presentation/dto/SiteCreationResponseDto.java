package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.application.SiteService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for site creation.
 * <p>
 * Includes the plaintext client secret which is ONLY shown at creation time.
 * This secret cannot be retrieved later, so clients must store it securely.
 * </p>
 *
 * @param id           Site unique identifier
 * @param accountId    Parent account identifier
 * @param domain       Site domain
 * @param name         Site display name
 * @param isActive     Whether site is active
 * @param createdAt    Site creation timestamp
 * @param clientSecret Plaintext client secret (ONLY shown at creation)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.site.presentation.SiteAdminController
 */
@Schema(description = "Site creation response with plaintext client secret (one-time only)")
public record SiteCreationResponseDto(
        @Schema(description = "Site unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Parent account identifier", example = "987fcdeb-51a2-43f7-9c3d-123456789abc")
        UUID accountId,

        @Schema(description = "Site domain", example = "example.com")
        String domain,

        @Schema(description = "Site display name", example = "Example Website")
        String name,

        @Schema(description = "Whether site is active", example = "true")
        boolean isActive,

        @Schema(description = "Site creation timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant createdAt,

        @Schema(description = "Plaintext client secret (only shown at creation)", example = "secret_abc123xyz")
        String clientSecret
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
                result.site().getDomain(),
                result.site().getDisplayName(),
                result.site().getIsActive(),
                result.site().getCreatedAt().toInstant(ZoneOffset.UTC),
                result.plaintextSecret()
        );
    }
}
