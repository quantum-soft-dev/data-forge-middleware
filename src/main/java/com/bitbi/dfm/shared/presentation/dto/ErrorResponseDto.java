package com.bitbi.dfm.shared.presentation.dto;

import java.time.Instant;

/**
 * Standardized error response DTO for all API endpoints.
 *
 * Used to provide consistent error responses across all controllers,
 * including authentication failures, validation errors, and business logic exceptions.
 *
 * FR-004: All error responses use this standardized DTO structure
 * FR-014: Authentication failures use generic messages
 */
public record ErrorResponseDto(
    Instant timestamp,
    Integer status,
    String error,
    String message,
    String path
) {
}
