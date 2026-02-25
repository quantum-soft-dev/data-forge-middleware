package com.bitbi.dfm.batch.presentation.dto;

import com.bitbi.dfm.batch.domain.BatchType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for starting a new batch.
 *
 * <p>Requires {@code batchType} to determine file validation and SQL generation behavior.
 * Schema version is pinned from the current site schema at batch start time (not client-provided).</p>
 *
 * Feature: 021-unified-upload-api (US8)
 *
 * @param batchType         BASELINE or DELTA (required)
 * @param expectedFileCount client-declared expected file count (optional, for progress tracking)
 * @param description       human-readable batch description (optional)
 */
public record BatchStartRequestDto(

        @NotNull(message = "batchType is required")
        BatchType batchType,

        @Min(value = 1, message = "expectedFileCount must be at least 1")
        Integer expectedFileCount,

        @Size(max = 500, message = "description must not exceed 500 characters")
        String description
) {}
