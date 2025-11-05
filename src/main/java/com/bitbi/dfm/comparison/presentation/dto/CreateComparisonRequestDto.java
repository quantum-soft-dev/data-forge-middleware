package com.bitbi.dfm.comparison.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a new file comparison.
 *
 * <p>This DTO is used in the POST /api/v1/comparisons endpoint to initiate
 * a comparison operation between two upload sessions.
 *
 * @param currentBatchId the current batch ID (source)
 * @param targetBatchId the target batch ID (comparison baseline)
 * @param fileIds list of file IDs to compare (null = compare all files in the batch)
 */
@Schema(description = "Request to create a file comparison between two upload sessions")
public record CreateComparisonRequestDto(
    @NotNull(message = "Current batch ID is required")
    @Schema(description = "ID of the current batch (source)", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    UUID currentBatchId,

    @NotNull(message = "Target batch ID is required")
    @Schema(description = "ID of the target batch (comparison baseline)", example = "550e8400-e29b-41d4-a716-446655440001", required = true)
    UUID targetBatchId,

    @Schema(description = "Optional list of file IDs to compare. If null, all files will be compared.", example = "[\"550e8400-e29b-41d4-a716-446655440010\", \"550e8400-e29b-41d4-a716-446655440011\"]")
    List<UUID> fileIds
) {
    /**
     * Validates business rules for the request.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (currentBatchId.equals(targetBatchId)) {
            throw new IllegalArgumentException(
                "Cannot compare a batch with itself (currentBatchId == targetBatchId)"
            );
        }

        if (fileIds != null && fileIds.isEmpty()) {
            throw new IllegalArgumentException(
                "File IDs list cannot be empty. Use null to compare all files."
            );
        }
    }
}
