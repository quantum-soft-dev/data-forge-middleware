package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for mark-as-read operations.
 *
 * @param markedCount Number of errors actually marked as read
 */
@Schema(description = "Response for mark-as-read operations")
public record MarkAsReadResponseDto(
        @Schema(description = "Number of errors actually marked as read")
        int markedCount
) {
}
