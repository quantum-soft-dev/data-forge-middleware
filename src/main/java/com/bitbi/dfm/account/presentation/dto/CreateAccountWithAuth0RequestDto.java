package com.bitbi.dfm.account.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating an account with Auth0 integration.
 * <p>
 * This DTO is used by admin users to create new accounts that are automatically
 * linked to Auth0 for authentication.
 * </p>
 *
 * Validation Rules:
 * - email: required, valid email format
 * - name: required, 2-100 characters
 * - phone: optional, E.164 format (7-15 digits, optional + prefix)
 * - company: optional, max 100 characters
 *
 * User Story: US1 - Admin Creates User Account via Auth0
 *
 * @param email The user's email address (used for Auth0 login)
 * @param name The user's full name
 * @param phone The user's phone number (optional, E.164 format)
 * @param company The user's company name (optional)
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record CreateAccountWithAuth0RequestDto(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    String email,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    String name,

    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Phone must be in E.164 format (7-15 digits, optional + prefix)")
    @Size(max = 20, message = "Phone must not exceed 20 characters")
    String phone,

    @Size(max = 100, message = "Company must not exceed 100 characters")
    String company
) {
}
