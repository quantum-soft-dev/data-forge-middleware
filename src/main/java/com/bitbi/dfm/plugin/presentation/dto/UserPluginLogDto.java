package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

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
        Instant occurredAt
) {
    /**
     * Creates a DTO from a PluginAuditLog entity.
     * Excludes sensitive fields like clientId, ipAddress, userAgent.
     */
    public static UserPluginLogDto fromEntity(PluginAuditLog entity) {
        return new UserPluginLogDto(
                entity.getId(),
                entity.getActionType(),
                entity.isSuccess(),
                entity.getErrorMessage(),
                entity.getMetadata(),
                entity.getOccurredAt()
        );
    }
}
