package com.bitbi.dfm.site.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for force rebaseline admin endpoint.
 *
 * Feature: 021-unified-upload-api
 * User Story: US3 - Admin Forces Rebaseline
 */
public record ForceRebaselineRequestDto(
        @NotBlank(message = "Reason is required")
        @Size(max = 1000, message = "Reason must not exceed 1000 characters")
        String reason
) {}
