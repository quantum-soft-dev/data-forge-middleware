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
 * @param id Unique account identifier
 * @param email Account email address
 * @param name Account display name
 * @param isActive Active status
 * @param createdAt Creation timestamp
 * @param maxConcurrentBatches Maximum concurrent batches allowed (default: 5)
 */
public record AccountResponseDto(
    UUID id,
    String email,
    String name,
    Boolean isActive,
    Instant createdAt,
    Integer maxConcurrentBatches
) {

    /**
     * Convert Account domain entity to AccountResponseDto.
     *
     * Maps all non-sensitive fields from entity to DTO, converting:
     * - LocalDateTime timestamp to Instant (UTC)
     * - Excludes sensitive fields (passwords, secrets)
     * - Sets maxConcurrentBatches to default value of 5
     *
     * @param account The domain entity to convert
     * @return AccountResponseDto with all fields mapped
     */
    public static AccountResponseDto fromEntity(Account account) {
        return new AccountResponseDto(
            account.getId(),
            account.getEmail(),
            account.getName(),
            account.getIsActive(),
            account.getCreatedAt().toInstant(ZoneOffset.UTC),
            5 // Default max concurrent batches per account
        );
    }
}
