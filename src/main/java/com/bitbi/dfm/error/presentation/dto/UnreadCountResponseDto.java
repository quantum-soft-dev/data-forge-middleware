package com.bitbi.dfm.error.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for unread count query.
 *
 * @param count Number of unread global errors
 */
@Schema(description = "Unread global errors count")
public record UnreadCountResponseDto(
        @Schema(description = "Number of unread global errors")
        long count
) {
}
