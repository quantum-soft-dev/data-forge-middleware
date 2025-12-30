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
     * Derives table name from original file name by removing file extension and sanitizing.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Remove .csv.gz or .csv extension</li>
     *   <li>Replace invalid characters with underscore</li>
     *   <li>Prefix with underscore if name starts with digit</li>
     * </ol>
     *
     * @param originalFileName Original file name (e.g., "customers.csv.gz" or "123_data.csv")
     * @return Table name without extension, sanitized (e.g., "customers" or "_123_data")
     * @throws IllegalArgumentException if original file name is blank or results in empty table name
     */
    public static String deriveTableName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("Original file name cannot be blank");
        }

        String name = originalFileName;

        // Remove extensions
        if (name.endsWith(".csv.gz")) {
            name = name.substring(0, name.length() - 7);
        } else if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }

        // Validate result is not empty
        if (name.isBlank()) {
            throw new IllegalArgumentException("Derived table name is empty for file: " + originalFileName);
        }

        // Sanitize: replace invalid characters with underscore (keep alphanumeric and underscore only)
        if (!name.matches("^[a-zA-Z0-9_]+$")) {
            name = name.replaceAll("[^a-zA-Z0-9_]", "_");
        }

        // Prefix with underscore if starts with digit (SQL table names can't start with digits)
        if (Character.isDigit(name.charAt(0))) {
            name = "_" + name;
        }

        return name;
    }
}
