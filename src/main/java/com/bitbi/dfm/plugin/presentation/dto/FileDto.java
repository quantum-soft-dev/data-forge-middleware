package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * DTO representing a CSV file in the Plugin API response.
 *
 * @param fileName Original file name (e.g., "customers.csv.gz")
 * @param fileSize Size of the file in bytes
 * @param lastUpdatedAt Timestamp of the latest upload
 */
@Schema(description = "CSV file information")
public record FileDto(
    @Schema(description = "Original file name", example = "customers.csv.gz")
    String fileName,

    @Schema(description = "Size of the file in bytes", example = "1048576")
    long fileSize,

    @Schema(description = "Timestamp of the latest upload", example = "2025-01-15T10:30:00Z")
    Instant lastUpdatedAt
) {
    /**
     * Creates a FileDto from individual values.
     */
    public static FileDto of(String fileName, long fileSize, Instant lastUpdatedAt) {
        return new FileDto(fileName, fileSize, lastUpdatedAt);
    }
}
