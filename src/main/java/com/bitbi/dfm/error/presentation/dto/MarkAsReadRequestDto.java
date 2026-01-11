package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for marking multiple errors as read.
 *
 * @param errorIds List of error IDs to mark as read (1-100 items)
 */
@Schema(description = "Request body for marking multiple errors as read")
public record MarkAsReadRequestDto(
        @Schema(
                description = "List of error IDs to mark as read",
                minLength = 1,
                maxLength = 100
        )
        @NotEmpty(message = "Error IDs list cannot be empty")
        @Size(max = 100, message = "Cannot mark more than 100 errors at once")
        List<UUID> errorIds
) {
}
