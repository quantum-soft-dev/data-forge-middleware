package com.bitbi.dfm.plugin.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * DTO representing a table (uploaded file) in the Plugin API response.
 *
 * @param tableName Table name (derived from original file name, without .csv/.csv.gz extension)
 * @param fileSize Size of the latest uploaded file in bytes
 * @param lastUpdatedAt Timestamp of the latest upload
 */
@Schema(description = "Table information")
public record TableDto(
    @Schema(description = "Table name (derived from original file name)", example = "customers")
    String tableName,

    @Schema(description = "Size of the latest uploaded file in bytes", example = "1048576")
    long fileSize,

    @Schema(description = "Timestamp of the latest upload", example = "2025-01-15T10:30:00Z")
    Instant lastUpdatedAt
) {
    /**
     * Creates a TableDto from individual values.
     */
    public static TableDto of(String tableName, long fileSize, Instant lastUpdatedAt) {
        return new TableDto(tableName, fileSize, lastUpdatedAt);
    }

    /**
     * Derives table name from original file name by removing file extension.
     *
     * @param originalFileName Original file name (e.g., "customers.csv.gz" or "products.csv")
     * @return Table name without extension (e.g., "customers" or "products")
     */
    public static String deriveTableName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "";
        }
        String name = originalFileName;
        if (name.endsWith(".csv.gz")) {
            name = name.substring(0, name.length() - 7);
        } else if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }
}
