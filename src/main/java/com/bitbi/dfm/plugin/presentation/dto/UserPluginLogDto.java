package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for user-facing plugin log entries.
 * Excludes sensitive data like client IDs and IP addresses.
 *
 * @param id          the log entry ID
 * @param actionType  the type of action (ACTIVATE, SQL_GENERATION_COMPLETED, etc.)
 * @param success     whether the operation succeeded
 * @param errorMessage error message if operation failed (null if success)
 * @param metadata    structured event metadata (batchId, stats, etc.)
 * @param occurredAt  when the event occurred
 * @param siteId      site identifier (extracted from metadata for SQL-related events)
 * @param siteDomain  site domain name (looked up from siteId)
 */
@Schema(description = "Plugin log entry for user-facing audit trail")
public record UserPluginLogDto(
        @Schema(description = "Log entry ID", example = "123")
        Long id,

        @Schema(description = "Type of action", example = "SQL_GENERATION_COMPLETED")
        PluginActionType actionType,

        @Schema(description = "Whether the operation succeeded", example = "true")
        boolean success,

        @Schema(description = "Error message if operation failed", example = "Table name contains invalid characters")
        String errorMessage,

        @Schema(description = "Structured event metadata (batchId, stats, s3Key, etc.)")
        Map<String, Object> metadata,

        @Schema(description = "When the event occurred", example = "2025-01-01T10:30:00Z")
        Instant occurredAt,

        @Schema(description = "Site identifier (for SQL-related events)", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID siteId,

        @Schema(description = "Site domain name", example = "example.com")
        String siteDomain
) {
    /**
     * Creates a DTO from a PluginAuditLog entity.
     * Excludes sensitive fields like clientId, ipAddress, userAgent.
     * Site info is not populated - use fromEntityWithSite for that.
     */
    public static UserPluginLogDto fromEntity(PluginAuditLog entity) {
        UUID siteId = extractSiteId(entity.getMetadata());
        return new UserPluginLogDto(
                entity.getId(),
                entity.getActionType(),
                entity.isSuccess(),
                entity.getErrorMessage(),
                entity.getMetadata(),
                entity.getOccurredAt(),
                siteId,
                null // siteDomain is populated by the service layer
        );
    }

    /**
     * Creates a DTO from a PluginAuditLog entity with site information.
     *
     * @param entity the audit log entity
     * @param siteDomain the site domain (looked up from siteId)
     * @return the DTO with site information
     */
    public static UserPluginLogDto fromEntityWithSite(PluginAuditLog entity, String siteDomain) {
        UUID siteId = extractSiteId(entity.getMetadata());
        return new UserPluginLogDto(
                entity.getId(),
                entity.getActionType(),
                entity.isSuccess(),
                entity.getErrorMessage(),
                entity.getMetadata(),
                entity.getOccurredAt(),
                siteId,
                siteDomain
        );
    }

    /**
     * Extracts siteId from metadata if present.
     */
    private static UUID extractSiteId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object siteIdObj = metadata.get("siteId");
        if (siteIdObj == null) {
            return null;
        }
        if (siteIdObj instanceof UUID) {
            return (UUID) siteIdObj;
        }
        try {
            return UUID.fromString(siteIdObj.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
