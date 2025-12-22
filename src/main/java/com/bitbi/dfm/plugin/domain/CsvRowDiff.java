package com.bitbi.dfm.plugin.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value object representing a single row difference between CSV files.
 * Used to track added, modified, or deleted rows for SQL generation.
 *
 * @param type The type of difference (ADDED, MODIFIED, DELETED)
 * @param lineNumber The line number in the source CSV file (1-based, excluding header)
 * @param values The column values for this row (column name -> value, may contain null values)
 * @param changedColumns For MODIFIED rows only: the old values of changed columns
 */
public record CsvRowDiff(
    DiffType type,
    int lineNumber,
    Map<String, String> values,
    Map<String, String> changedColumns
) {
    /**
     * Types of row differences.
     */
    public enum DiffType {
        /** Row exists in current batch but not in previous - generates INSERT */
        ADDED,
        /** Row exists in both batches but with different values - generates UPDATE */
        MODIFIED,
        /** Row exists in previous batch but not in current - generates DELETE */
        DELETED
    }

    /**
     * Creates a diff representing an added row (INSERT).
     */
    public static CsvRowDiff added(int lineNumber, Map<String, String> values) {
        return new CsvRowDiff(DiffType.ADDED, lineNumber, copyMapNullSafe(values), Map.of());
    }

    /**
     * Creates a diff representing a deleted row (DELETE).
     */
    public static CsvRowDiff deleted(int lineNumber, Map<String, String> values) {
        return new CsvRowDiff(DiffType.DELETED, lineNumber, copyMapNullSafe(values), Map.of());
    }

    /**
     * Creates a diff representing a modified row (UPDATE).
     *
     * @param lineNumber The line number in the current CSV
     * @param newValues The new column values
     * @param changedColumns Map of column name -> old value for columns that changed
     */
    public static CsvRowDiff modified(int lineNumber, Map<String, String> newValues, Map<String, String> changedColumns) {
        return new CsvRowDiff(DiffType.MODIFIED, lineNumber, copyMapNullSafe(newValues), copyMapNullSafe(changedColumns));
    }

    /**
     * Creates an unmodifiable copy of a map that may contain null values.
     * Map.copyOf() doesn't allow null values, so we use LinkedHashMap + unmodifiableMap.
     */
    private static Map<String, String> copyMapNullSafe(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Returns the columns that were NOT changed (used for WHERE clause in UPDATE).
     */
    public Map<String, String> unchangedColumns() {
        if (type != DiffType.MODIFIED) {
            return values;
        }
        return values.entrySet().stream()
            .filter(e -> !changedColumns.containsKey(e.getKey()))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
