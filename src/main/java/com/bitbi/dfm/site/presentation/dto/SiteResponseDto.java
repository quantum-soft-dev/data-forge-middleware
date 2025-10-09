package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.domain.Site;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for site data.
 *
 * Provides immutable representation of site data for API responses.
 * Excludes clientSecretHash for security.
 *
 * FR-001: Structured response objects
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation (non-sensitive)
 *
 * @param id Unique site identifier
 * @param accountId Account this site belongs to
 * @param domain Site domain name
 * @param name Site display name
 * @param isActive Active status
 * @param createdAt Creation timestamp
 */
public record SiteResponseDto(
    UUID id,
    UUID accountId,
    String domain,
    String name,
    Boolean isActive,
    Instant createdAt
) {

    /**
     * Convert Site domain entity to SiteResponseDto.
     *
     * Maps all non-sensitive fields from entity to DTO, converting:
     * - displayName to name
     * - LocalDateTime timestamp to Instant (UTC)
     * - Excludes clientSecretHash for security
     *
     * @param site The domain entity to convert
     * @return SiteResponseDto with all fields mapped
     */
    public static SiteResponseDto fromEntity(Site site) {
        return new SiteResponseDto(
            site.getId(),
            site.getAccountId(),
            site.getDomain(),
            site.getDisplayName(),
            site.getIsActive(),
            site.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
