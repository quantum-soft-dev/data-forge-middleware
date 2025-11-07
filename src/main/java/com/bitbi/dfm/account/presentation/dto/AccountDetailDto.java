package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for account details with Auth0-specific fields.
 * <p>
 * This DTO is returned when listing accounts with Auth0 integration.
 * Extends basic account information with Auth0 user metadata (blocked status, last login).
 * </p>
 *
 * User Story: US6 - Admin Views List of Users with Auth0 Integration
 * Task: T065 - Create AccountDetailDto.java
 *
 * @param id The account's unique identifier (PostgreSQL UUID)
 * @param email The user's email address
 * @param name The user's full name
 * @param phone The user's phone number (nullable)
 * @param company The user's company name (nullable)
 * @param isActive Whether the account is active in PostgreSQL
 * @param identityProviderUserId The identity provider user ID (e.g., auth0|{id})
 * @param isBlocked Whether the user is blocked in Auth0 (fetched from Management API)
 * @param lastLogin The user's last login timestamp from Auth0 (nullable if never logged in)
 * @param createdAt Account creation timestamp
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record AccountDetailDto(
    UUID id,
    String email,
    String name,
    String phone,
    String company,
    boolean isActive,
    String identityProviderUserId,
    boolean isBlocked,
    Instant lastLogin,
    Instant createdAt
) {
    /**
     * Factory method to create response from Account entity and Auth0 user details.
     * <p>
     * This method combines PostgreSQL account data with Auth0 user metadata.
     * Used for list operations where Auth0 fields are required.
     * </p>
     *
     * @param account The account entity from PostgreSQL
     * @param isBlocked Whether the user is blocked in Auth0
     * @param lastLogin The user's last login timestamp from Auth0 (nullable)
     * @return Response DTO with Auth0 fields populated
     */
    public static AccountDetailDto fromEntity(Account account, boolean isBlocked, Instant lastLogin) {
        return new AccountDetailDto(
            account.getId(),
            account.getEmail(),
            account.getName(),
            account.getPhone() != null ? account.getPhone().getValue() : null,
            account.getCompany() != null ? account.getCompany().getValue() : null,
            account.getIsActive(),
            account.getIdentityProviderUserId(),
            isBlocked,
            lastLogin,
            Instant.ofEpochSecond(account.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC))
        );
    }

    /**
     * Factory method to create response from Account entity without Auth0 enrichment.
     * <p>
     * Used when Auth0 details are not available (e.g., Auth0 service unavailable).
     * Sets isBlocked=false and lastLogin=null as defaults.
     * </p>
     *
     * @param account The account entity from PostgreSQL
     * @return Response DTO with default Auth0 field values
     */
    public static AccountDetailDto fromEntityWithoutAuth0(Account account) {
        return fromEntity(account, false, null);
    }
}
