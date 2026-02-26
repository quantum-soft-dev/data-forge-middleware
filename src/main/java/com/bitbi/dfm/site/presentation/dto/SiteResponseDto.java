package com.bitbi.dfm.site.presentation.dto;

import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteType;

import java.time.Instant;
import java.time.LocalDateTime;
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
 * @param siteType Site type (DBF, POSTGRES_CDC, MSSQL_CDC, or DBF_CDC)
 * @param lastHeartbeatAt Last heartbeat timestamp (nullable)
 * @param forceFullUpload Whether force full upload directive is active
 * @param forceFullUploadReason Reason for force full upload (nullable)
 * @param requestLogs Whether log request directive is active
 * @param requestLogsMessage Message for log request directive (nullable)
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
    Instant lastHeartbeatAt,
    Boolean forceFullUpload,
    String forceFullUploadReason,
    Boolean requestLogs,
    String requestLogsMessage
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
            toInstant(site.getLastHeartbeatAt()),
            site.getForceFullUpload(),
            site.getForceFullUploadReason() != null ? site.getForceFullUploadReason().name() : null,
            site.getRequestLogs(),
            site.getRequestLogsMessage()
        );
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.toInstant(ZoneOffset.UTC) : null;
    }
}
