package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.domain.ActionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for admin action log entries.
 * <p>
 * Used to display audit trail of administrative actions
 * performed on user accounts (create, lock, unlock, reset password).
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Admin action log entry from audit trail")
public record AdminActionLogResponseDto(
        @Schema(description = "Log entry ID", example = "1")
        Long id,

        @Schema(description = "Type of action performed", example = "LOCK_ACCOUNT")
        AdminActionType actionType,

        @Schema(description = "Target account ID (account that was acted upon)", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID targetAccountId,

        @Schema(description = "Admin account ID (who performed the action)", example = "660e9500-f39c-51e5-b827-557766551111")
        UUID adminAccountId,

        @Schema(description = "Action execution status", example = "SUCCESS")
        ActionStatus status,

        @Schema(description = "Error message (if action failed)", example = "Account not found")
        String errorMessage,

        @Schema(description = "IP address of admin", example = "192.168.1.100")
        String ipAddress,

        @Schema(description = "User agent of admin's browser", example = "Mozilla/5.0")
        String userAgent,

        @Schema(description = "Timestamp when action was performed", example = "2025-10-29T10:15:30Z")
        Instant createdAt
) {
    /**
     * Create DTO from AdminActionLog entity.
     *
     * @param entity AdminActionLog domain entity
     * @return AdminActionLogResponseDto
     */
    public static AdminActionLogResponseDto fromEntity(AdminActionLog entity) {
        return new AdminActionLogResponseDto(
                entity.getId(),
                entity.getActionType(),
                entity.getTargetAccountId(),
                entity.getAdminAccountId(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getIpAddress(),
                entity.getUserAgent(),
                entity.getCreatedAt()
        );
    }
}
