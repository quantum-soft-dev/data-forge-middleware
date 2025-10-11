package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new account.
 * <p>
 * Replaces Map<String, Object> input for account creation endpoint.
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * </p>
 *
 * @param email Account email address (unique, required)
 * @param name  Account display name (2-100 characters, required)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.account.presentation.AccountAdminController
 */
@Schema(description = "Request body for creating a new account")
public record CreateAccountRequestDto(

        @Schema(
                description = "Account email address (unique)",
                example = "admin@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid format")
        String email,

        @Schema(
                description = "Account display name",
                example = "John Doe",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
        String name
) {
}
