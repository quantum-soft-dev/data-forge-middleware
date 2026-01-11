package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for marking multiple errors as read.
 * <p>
 * Validates that the errorIds list is non-null, non-empty, and contains at most 100 items
 * to prevent performance issues with large IN clauses.
 * </p>
 *
 * @param errorIds List of error IDs to mark as read (1-100 items)
 */
@Schema(description = "Request body for marking multiple errors as read")
public record MarkAsReadRequestDto(
        @Schema(
                description = "List of error IDs to mark as read",
                minLength = 1,
                maxLength = 100,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "errorIds cannot be null")
        @Size(min = 1, max = 100, message = "errorIds must contain 1-100 IDs")
        List<UUID> errorIds
) {
}
