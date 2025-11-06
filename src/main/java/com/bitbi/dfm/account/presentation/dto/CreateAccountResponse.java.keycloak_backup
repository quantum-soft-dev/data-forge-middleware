package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for account creation with temporary password.
 * <p>
 * Contains the newly created account information along with the
 * generated temporary password that must be displayed to the admin
 * exactly once.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Account creation response with temporary password")
public record CreateAccountResponse(
        @Schema(description = "Created account with Keycloak status")
        AccountWithKeycloakResponse account,

        @Schema(
                description = "Temporary password (display once, user must change on first login)",
                example = "TempPass123!"
        )
        String temporaryPassword
) {
}
