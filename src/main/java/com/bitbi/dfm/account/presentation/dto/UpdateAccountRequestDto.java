package com.bitbi.dfm.account.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an existing account.
 * <p>
 * Replaces Map<String, Object> input for account update endpoint.
 * Provides type safety and automatic validation via Jakarta Bean Validation.
 * All fields are optional - only provided fields will be updated.
 * </p>
 *
 * @param name    Account display name (2-100 characters, optional)
 * @param phone   Account phone number (optional)
 * @param company Account company name (optional)
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.account.presentation.AccountAdminController
 */
@Schema(description = "Request body for updating an existing account")
public record UpdateAccountRequestDto(

        @Schema(
                description = "New account display name",
                example = "Jane Smith",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(min = 2, max = 100, message = "Name must be 2-100 characters")
        String name,

        @Schema(
                description = "New phone number",
                example = "+1234567890",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(max = 50, message = "Phone must not exceed 50 characters")
        String phone,

        @Schema(
                description = "New company name",
                example = "Acme Corp",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(max = 255, message = "Company must not exceed 255 characters")
        String company
) {
}
