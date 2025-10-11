package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an existing account.
 * <p>
 * Replaces Map<String, Object> input for account update endpoint.
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * </p>
 *
 * @param name Account display name (2-100 characters, required)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.account.presentation.AccountAdminController
 */
@Schema(description = "Request body for updating an existing account")
public record UpdateAccountRequestDto(

        @Schema(
                description = "New account display name",
                example = "Jane Smith",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
        String name
) {
}
