package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for password reset operation.
 * <p>
 * Contains the account ID, new temporary password, and expiration timestamp.
 * The temporary password must be displayed to the admin exactly once.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Password reset response with temporary password")
public record ResetPasswordResponse(
        @Schema(description = "Account unique identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID accountId,

        @Schema(
                description = "New temporary password (display once, user must change on next login)",
                example = "NewPass456#"
        )
        String temporaryPassword,

        @Schema(description = "Password expiration timestamp (30 days from now)")
        Instant expiresAt
) {
}
