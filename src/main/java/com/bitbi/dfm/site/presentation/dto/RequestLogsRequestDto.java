package com.bitbi.dfm.site.presentation.dto;

import jakarta.validation.constraints.Size;

/**
 * Request DTO for request-logs admin endpoint.
 *
 * Feature: 021-unified-upload-api
 * User Story: US3 - Admin Forces Rebaseline
 */
public record RequestLogsRequestDto(
        @Size(max = 500, message = "Message must not exceed 500 characters")
        String message
) {}
