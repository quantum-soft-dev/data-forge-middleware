package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import org.keycloak.representations.idm.UserRepresentation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for account data with Keycloak integration status.
 * <p>
 * Extends basic account information with Keycloak-specific fields
 * such as enabled status, temporary password flag, and login timestamps.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Schema(description = "Account with Keycloak authentication status")
public record AccountWithKeycloakResponse(
        @Schema(description = "Account unique identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Keycloak user UUID", example = "a3bb189e-8bf9-3888-9912-ace4e6543002")
        String keycloakUserId,

        @Schema(description = "Account email address", example = "user@example.com")
        String email,

        @Schema(description = "Account display name", example = "John Doe")
        String name,

        @Schema(description = "Account phone number", example = "+1234567890", nullable = true)
        String phone,

        @Schema(description = "Account company name", example = "Acme Corp", nullable = true)
        String company,

        @Schema(description = "Account active status (business logic)", example = "true")
        Boolean isActive,

        @Schema(description = "Keycloak user enabled status (authentication)", example = "true")
        Boolean keycloakEnabled,

        @Schema(description = "Password is temporary and requires change", example = "true")
        Boolean passwordTemporary,

        @Schema(description = "Password expiration timestamp (if temporary)", nullable = true)
        Instant passwordExpiresAt,

        @Schema(description = "Last successful login timestamp", nullable = true)
        Instant lastLogin,

        @Schema(description = "Account creation timestamp")
        Instant createdAt,

        @Schema(description = "Account last update timestamp")
        Instant updatedAt
) {

    /**
     * Convert Account entity and Keycloak UserRepresentation to response DTO.
     *
     * @param account Account domain entity
     * @param keycloakUser Keycloak user representation
     * @param lastLoginMillis Last login timestamp in milliseconds since epoch (nullable)
     * @return AccountWithKeycloakResponse
     */
    public static AccountWithKeycloakResponse fromEntity(Account account, UserRepresentation keycloakUser, Long lastLoginMillis) {
        boolean passwordTemporary = keycloakUser.getCredentials() != null &&
                keycloakUser.getCredentials().stream()
                        .anyMatch(cred -> "password".equals(cred.getType()) && Boolean.TRUE.equals(cred.isTemporary()));

        // Password expiration: 30 days from creation for temporary passwords
        Instant passwordExpiresAt = null;
        if (passwordTemporary && keycloakUser.getCreatedTimestamp() != null) {
            passwordExpiresAt = Instant.ofEpochMilli(keycloakUser.getCreatedTimestamp()).plusSeconds(30L * 24 * 60 * 60);
        }

        // Convert lastLogin from milliseconds to Instant
        Instant lastLogin = lastLoginMillis != null ? Instant.ofEpochMilli(lastLoginMillis) : null;

        return new AccountWithKeycloakResponse(
                account.getId(),
                account.getKeycloakUserId(),
                account.getEmail(),
                account.getName(),
                account.getPhone() != null ? account.getPhone().getValue() : null,
                account.getCompany() != null ? account.getCompany().getValue() : null,
                account.getIsActive(),
                keycloakUser.isEnabled(),
                passwordTemporary,
                passwordExpiresAt,
                lastLogin,
                account.getCreatedAt().toInstant(ZoneOffset.UTC),
                account.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
