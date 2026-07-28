package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.domain.ClientApiVersion;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for site data.
 *
 * Provides immutable representation of site data for API responses.
 * Excludes clientSecretHash for security.
 *
 * @param id Unique site identifier
 * @param accountId Account this site belongs to
 * @param siteName Site name (unique within account)
 * @param name Site display name
 * @param isActive Active status
 * @param retentionDays Retention period in days for batch cleanup
 * @param createdAt Creation timestamp
 * @param siteType Site type (DBF or POSTGRES_CDC)
 * @param clientApiVersion Client ingestion API version (Delta gRPC)
 */
public record SiteResponseDto(
    UUID id,
    UUID accountId,
    String siteName,
    String name,
    Boolean isActive,
    Integer retentionDays,
    Instant createdAt,
    SiteType siteType,
    ClientApiVersion clientApiVersion
) {

    /**
     * Convert Site domain entity to SiteResponseDto.
     *
     * @param site The domain entity to convert
     * @return SiteResponseDto with all fields mapped
     */
    public static SiteResponseDto fromEntity(Site site) {
        return new SiteResponseDto(
            site.getId(),
            site.getAccountId(),
            site.getSiteName(),
            site.getDisplayName(),
            site.getIsActive(),
            site.getRetentionDays(),
            site.getCreatedAt().toInstant(ZoneOffset.UTC),
            site.getSiteType(),
            site.getClientApiVersion()
        );
    }
}
