package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for plugin audit log entries in admin API responses.
 *
 * <p>Maps from PluginAuditLog entity for REST API responses.</p>
 *
 * <p>User Story 6 (Phase 8) - Admin Views Plugin Audit Trail</p>
 */
@Schema(description = "Plugin audit log entry")
public record PluginAuditLogEntryDto(
        @Schema(description = "Audit log entry ID", example = "12345")
        Long id,

        @Schema(description = "Plugin identifier", example = "bit-bi")
        String pluginId,

        @Schema(description = "Account ID (may be null for non-account operations)")
        UUID accountId,

        @Schema(description = "OAuth client ID that made the request")
        String clientId,

        @Schema(description = "Type of action performed",
                allowableValues = {"ACTIVATE", "DEACTIVATE", "REACTIVATE", "EVENT_DISPATCHED", "EVENT_FAILED", "EVENT_TIMEOUT"})
        PluginActionType actionType,

        @Schema(description = "SHA-256 hash of request body (base64 encoded)")
        String requestBodyHash,

        @Schema(description = "Request body size in bytes")
        Integer requestBodySize,

        @Schema(description = "HTTP response status code")
        Integer responseStatus,

        @Schema(description = "Whether the operation succeeded")
        boolean success,

        @Schema(description = "Error message if operation failed")
        String errorMessage,

        @Schema(description = "Client IP address")
        String ipAddress,

        @Schema(description = "Operation duration in milliseconds")
        Long durationMs,

        @Schema(description = "When the operation occurred")
        Instant occurredAt
) {

    /**
     * Creates a DTO from a PluginAuditLog entity.
     *
     * @param entity the audit log entity
     * @return the DTO
     */
    public static PluginAuditLogEntryDto fromEntity(PluginAuditLog entity) {
        return new PluginAuditLogEntryDto(
                entity.getId(),
                entity.getPluginId(),
                entity.getAccountId(),
                entity.getClientId(),
                entity.getActionType(),
                entity.getRequestBodyHash(),
                entity.getRequestBodySize(),
                entity.getResponseStatus(),
                entity.isSuccess(),
                entity.getErrorMessage(),
                entity.getIpAddress(),
                entity.getDurationMs(),
                entity.getOccurredAt()
        );
    }
}
