package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for Auth0 password reset operation.
 * <p>
 * Contains the account ID, password reset link (Auth0 password change ticket), and expiration timestamp.
 * The password reset link is a one-time use URL that directs the user to Auth0's password change page.
 * </p>
 *
 * User Story: US3 - Admin Resets User Password
 * Migration Note: Replaces temporaryPassword with passwordResetLink (Auth0 pattern)
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Auth0 password reset response with password reset link")
public record ResetPasswordResponseDto(
        @Schema(description = "Account unique identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID accountId,

        @Schema(description = "User email address", example = "user@example.com")
        String email,

        @Schema(
                description = "Auth0 password reset link (one-time use, expires in 24 hours)",
                example = "https://your-tenant.us.auth0.com/lo/reset?ticket=abc123xyz"
        )
        String passwordResetLink,

        @Schema(description = "Password reset link expiration timestamp (24 hours from now)")
        Instant expiresAt
) {
    /**
     * Factory method to create ResetPasswordResponseDto from account details and Auth0 ticket URL.
     *
     * @param accountId         account UUID
     * @param email             user email address
     * @param passwordResetLink Auth0 password change ticket URL
     * @param ttlSeconds        ticket time-to-live in seconds (default 86400 = 24 hours)
     * @return ResetPasswordResponseDto instance
     */
    public static ResetPasswordResponseDto of(UUID accountId, String email, String passwordResetLink, int ttlSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        return new ResetPasswordResponseDto(accountId, email, passwordResetLink, expiresAt);
    }

    /**
     * Factory method with default 24-hour expiry.
     *
     * @param accountId         account UUID
     * @param email             user email address
     * @param passwordResetLink Auth0 password change ticket URL
     * @return ResetPasswordResponseDto instance with 24-hour expiry
     */
    public static ResetPasswordResponseDto of(UUID accountId, String email, String passwordResetLink) {
        return of(accountId, email, passwordResetLink, 86400); // 24 hours default
    }
}
