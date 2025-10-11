package com.bitbi.dfm.account.presentation.dto;

import com.bitbi.dfm.account.domain.Account;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for account with statistics.
 * <p>
 * Combines basic account information with aggregated statistics
 * from related sites, batches, and files.
 * </p>
 *
 * @param id                  Account unique identifier
 * @param email               Account email address
 * @param name                Account display name
 * @param isActive            Whether account is active
 * @param createdAt           Account creation timestamp
 * @param sitesCount          Total number of sites
 * @param totalBatches        Total number of batches across all sites
 * @param totalUploadedFiles  Total number of uploaded files across all batches
 * @author Data Forge Team
 * @version 1.0.0
 * @see com.bitbi.dfm.account.presentation.AccountAdminController
 */
@Schema(description = "Account with aggregated statistics")
public record AccountWithStatsResponseDto(
        @Schema(description = "Account unique identifier", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID id,

        @Schema(description = "Account email address", example = "admin@example.com")
        String email,

        @Schema(description = "Account display name", example = "John Doe")
        String name,

        @Schema(description = "Whether account is active", example = "true")
        boolean isActive,

        @Schema(description = "Account creation timestamp (ISO-8601)", example = "2025-01-15T10:30:00Z")
        Instant createdAt,

        @Schema(description = "Total number of sites", example = "5")
        int sitesCount,

        @Schema(description = "Total number of batches across all sites", example = "120")
        int totalBatches,

        @Schema(description = "Total number of uploaded files across all batches", example = "1500")
        int totalUploadedFiles
) {
    /**
     * Create DTO from Account entity and statistics map.
     *
     * @param account    Account entity
     * @param statistics Statistics map with keys: totalSites, totalBatches, totalFiles
     * @return AccountWithStatsResponseDto
     */
    public static AccountWithStatsResponseDto fromEntityAndStats(Account account, Map<String, Object> statistics) {
        return new AccountWithStatsResponseDto(
                account.getId(),
                account.getEmail(),
                account.getName(),
                account.getIsActive(),
                account.getCreatedAt().toInstant(java.time.ZoneOffset.UTC),
                ((Number) statistics.getOrDefault("totalSites", 0)).intValue(),
                ((Number) statistics.getOrDefault("totalBatches", 0)).intValue(),
                ((Number) statistics.getOrDefault("totalFiles", 0)).intValue()
        );
    }
}
