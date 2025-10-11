package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Response DTO for account data (admin endpoints).
 *
 * Provides immutable representation of account data for API responses.
 * Excludes sensitive fields like passwords or internal metadata.
 *
 * FR-001: Structured response objects
 * FR-002: Consistent field naming and types
 * FR-003: Complete information preservation (non-sensitive)
 *
 * @param id                   Unique account identifier
 * @param email                Account email address
 * @param name                 Account display name
 * @param phone                Account phone number (nullable)
 * @param company              Account company name (nullable)
 * @param status               Active status ("active" or "inactive")
 * @param createdAt            Creation timestamp (ISO 8601)
 * @param maxConcurrentBatches Maximum concurrent batches allowed (default: 5)
 */
public record AccountResponseDto(
    UUID id,
    String email,
    String name,
    String phone,
    String company,
    String status,
    Instant createdAt,
    Integer maxConcurrentBatches
) {

    /**
     * Convert Account domain entity to AccountResponseDto.
     *
     * Maps all non-sensitive fields from entity to DTO, converting:
     * - LocalDateTime timestamp to Instant (UTC)
     * - Boolean isActive to String status ("active" or "inactive")
     * - Excludes sensitive fields (passwords, secrets)
     *
     * @param account The domain entity to convert
     * @param maxConcurrentBatches Maximum concurrent batches allowed for this account
     * @return AccountResponseDto with all fields mapped
     */
    public static AccountResponseDto fromEntity(Account account, int maxConcurrentBatches) {
        return new AccountResponseDto(
            account.getId(),
            account.getEmail(),
            account.getName(),
            account.getPhone() != null ? account.getPhone().getValue() : null,
            account.getCompany() != null ? account.getCompany().getValue() : null,
            account.getIsActive() ? "active" : "inactive",
            account.getCreatedAt().toInstant(ZoneOffset.UTC),
            maxConcurrentBatches
        );
    }
}
