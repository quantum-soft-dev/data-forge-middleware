package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for comparing CSV files and generating row-level diffs.
 * Used to detect added, modified, and deleted rows between batch uploads.
 *
 * <h2>Row Identity Detection</h2>
 * <p>
 * <strong>IMPORTANT:</strong> This service uses the <b>first column</b> of the CSV
 * as the row identity key for detecting modifications vs additions/deletions.
 * </p>
 *
 * <h3>Assumptions</h3>
 * <ul>
 *   <li>The first column contains a unique identifier (e.g., id, sku, email)</li>
 *   <li>The first column values are stable between batches</li>
 *   <li>Column order remains consistent between batches</li>
 * </ul>
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>If the first column is NOT unique (e.g., country, category), incorrect
 *       UPDATE/DELETE statements may be generated</li>
 *   <li>If a row exists with the same key in both batches but different values,
 *       it will be detected as MODIFIED</li>
 *   <li>Rows without a key value fall back to full row comparison (slower)</li>
 * </ul>
 *
 * <h3>Recommendations for CSV Structure</h3>
 * <ul>
 *   <li>Place the unique identifier column (id, primary key) as the first column</li>
 *   <li>Ensure identifier values are immutable and unique within the file</li>
 *   <li>Use consistent column ordering across all batch uploads</li>
 * </ul>
 *
 * @see CsvRowDiff
 */
@Service
public class CsvDiffService {

    private static final Logger log = LoggerFactory.getLogger(CsvDiffService.class);

    /**
     * Pattern for valid CSV column names.
     * Allows letters, digits, underscores, spaces, and common punctuation.
     * Maximum length 128 characters.
     */
    private static final Pattern VALID_COLUMN_NAME = Pattern.compile("^[\\p{L}\\p{N}_\\- .,()]{1,128}$");

    /**
     * Maximum allowed number of columns to prevent memory issues.
     */
    private static final int MAX_COLUMNS = 500;

    /**
     * Compares two lists of CSV rows and returns the differences.
     * Uses first column as row identity key for modification detection.
     *
     * @param previousRows Rows from the previous batch (empty for first batch)
     * @param currentRows Rows from the current batch
     * @param columnTypes Map of column name to DBF type (for type-aware comparison)
     * @return List of row differences (added, modified, deleted)
     * @throws InvalidCsvHeaderException if column headers are invalid
     */
    public List<CsvRowDiff> compare(
            List<Map<String, String>> previousRows,
            List<Map<String, String>> currentRows,
            Map<String, DbfColumnType> columnTypes
    ) {
        // Validate CSV headers before processing
        validateHeaders(currentRows);
        validateHeaders(previousRows);

        List<CsvRowDiff> diffs = new ArrayList<>();

        // Get identity column (first column) if available
        String identityColumn = getIdentityColumn(currentRows, previousRows);

        // Build lookup by identity column value
        Map<String, Map<String, String>> previousByKey = buildKeyLookup(previousRows, identityColumn);
        Map<String, Map<String, String>> currentByKey = buildKeyLookup(currentRows, identityColumn);

        // Track which previous keys were matched (for deletion detection)
        Set<String> matchedPreviousKeys = new HashSet<>();

        // Process current rows
        for (int i = 0; i < currentRows.size(); i++) {
            Map<String, String> currentRow = currentRows.get(i);
            int lineNumber = i + 1; // 1-based line number

            String key = getKeyValue(currentRow, identityColumn);

            if (key != null && previousByKey.containsKey(key)) {
                // Row with same key exists in previous
                Map<String, String> previousRow = previousByKey.get(key);
                matchedPreviousKeys.add(key);

                // Check if values differ
                Map<String, String> changedColumns = findChangedColumns(previousRow, currentRow);
                if (!changedColumns.isEmpty()) {
                    diffs.add(CsvRowDiff.modified(lineNumber, currentRow, changedColumns));
                }
                // else: unchanged row
            } else {
                // New row - ADDED
                diffs.add(CsvRowDiff.added(lineNumber, currentRow));
            }
        }

        // Detect DELETED rows (in previous but not matched in current)
        for (int i = 0; i < previousRows.size(); i++) {
            Map<String, String> previousRow = previousRows.get(i);
            int lineNumber = i + 1;

            String key = getKeyValue(previousRow, identityColumn);

            if (key != null && !matchedPreviousKeys.contains(key)) {
                // Not matched - was deleted
                diffs.add(CsvRowDiff.deleted(lineNumber, previousRow));
            } else if (key == null && !isRowInList(previousRow, currentRows)) {
                // No key column, use full row comparison
                diffs.add(CsvRowDiff.deleted(lineNumber, previousRow));
            }
        }

        return diffs;
    }

    /**
     * Gets the identity column name (first column from the rows).
     */
    private String getIdentityColumn(List<Map<String, String>> currentRows, List<Map<String, String>> previousRows) {
        if (!currentRows.isEmpty() && !currentRows.get(0).isEmpty()) {
            return currentRows.get(0).keySet().iterator().next();
        }
        if (!previousRows.isEmpty() && !previousRows.get(0).isEmpty()) {
            return previousRows.get(0).keySet().iterator().next();
        }
        return null;
    }

    /**
     * Builds a lookup map from identity column value to row.
     */
    private Map<String, Map<String, String>> buildKeyLookup(List<Map<String, String>> rows, String identityColumn) {
        Map<String, Map<String, String>> lookup = new HashMap<>();
        if (identityColumn == null) {
            return lookup;
        }
        for (Map<String, String> row : rows) {
            String key = getKeyValue(row, identityColumn);
            if (key != null) {
                lookup.put(key, row);
            }
        }
        return lookup;
    }

    /**
     * Gets the value of the identity column from a row.
     */
    private String getKeyValue(Map<String, String> row, String identityColumn) {
        if (identityColumn == null || row == null) {
            return null;
        }
        return row.get(identityColumn);
    }

    /**
     * Checks if a row exists in a list using full row comparison.
     */
    private boolean isRowInList(Map<String, String> row, List<Map<String, String>> rows) {
        String fingerprint = getRowFingerprint(row);
        for (Map<String, String> other : rows) {
            if (fingerprint.equals(getRowFingerprint(other))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds columns that changed between two rows.
     * Returns map of column name -> old value for changed columns.
     */
    private Map<String, String> findChangedColumns(Map<String, String> previousRow, Map<String, String> currentRow) {
        Map<String, String> changedColumns = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : currentRow.entrySet()) {
            String column = entry.getKey();
            String currentValue = entry.getValue();
            String previousValue = previousRow.get(column);

            // Compare values directly (empty string is different from null)
            if (!Objects.equals(currentValue, previousValue)) {
                changedColumns.put(column, previousValue);
            }
        }

        return changedColumns;
    }

    /**
     * Creates a fingerprint for a row based on all column values.
     * Order of columns is normalized for consistent comparison.
     */
    private String getRowFingerprint(Map<String, String> row) {
        if (row == null) {
            return "";
        }
        return row.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + (e.getValue() == null ? "\0NULL\0" : e.getValue()))
            .collect(Collectors.joining("|"));
    }

    /**
     * Validates CSV headers for security and sanity.
     * Checks:
     * - Column count limit
     * - Column name format (no SQL injection vectors)
     * - No empty column names
     *
     * @param rows CSV rows to validate headers from
     * @throws InvalidCsvHeaderException if headers are invalid
     */
    private void validateHeaders(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return; // Empty is valid
        }

        Map<String, String> firstRow = rows.get(0);
        if (firstRow == null || firstRow.isEmpty()) {
            return; // Empty row is valid
        }

        Set<String> columns = firstRow.keySet();

        // Check column count
        if (columns.size() > MAX_COLUMNS) {
            log.error("CSV has too many columns: count={}, max={}",
                    columns.size(), MAX_COLUMNS);
            throw new InvalidCsvHeaderException(
                    "CSV file has " + columns.size() + " columns, exceeding limit of " + MAX_COLUMNS);
        }

        // Validate each column name
        List<String> invalidColumns = new ArrayList<>();
        for (String column : columns) {
            if (column == null || column.trim().isEmpty()) {
                invalidColumns.add("<empty>");
                continue;
            }

            if (!VALID_COLUMN_NAME.matcher(column).matches()) {
                invalidColumns.add(sanitizeForLogging(column));
            }
        }

        if (!invalidColumns.isEmpty()) {
            log.error("CSV has invalid column names: columns={}",
                    String.join(", ", invalidColumns));
            throw new InvalidCsvHeaderException(
                    "CSV file has " + invalidColumns.size() + " invalid column name(s). " +
                    "Column names must contain only letters, numbers, underscores, spaces, and common punctuation.");
        }
    }

    /**
     * Sanitizes a string for safe logging (prevents log injection).
     */
    private String sanitizeForLogging(String value) {
        if (value == null) {
            return "<null>";
        }
        // Truncate and escape control characters
        String truncated = value.length() > 50 ? value.substring(0, 50) + "..." : value;
        return truncated.replaceAll("[\\r\\n\\t]", " ")
                        .replaceAll("[^\\p{Print}]", "?");
    }

    /**
     * Exception thrown when CSV headers are invalid.
     */
    public static class InvalidCsvHeaderException extends RuntimeException {
        public InvalidCsvHeaderException(String message) {
            super(message);
        }
    }
}
