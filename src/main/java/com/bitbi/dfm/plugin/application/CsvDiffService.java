package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for comparing CSV files and generating row-level diffs.
 * Used to detect added, modified, and deleted rows between batch uploads.
 */
@Service
public class CsvDiffService {

    /**
     * Compares two lists of CSV rows and returns the differences.
     * Uses first column as row identity key for modification detection.
     *
     * @param previousRows Rows from the previous batch (empty for first batch)
     * @param currentRows Rows from the current batch
     * @param columnTypes Map of column name to DBF type (for type-aware comparison)
     * @return List of row differences (added, modified, deleted)
     */
    public List<CsvRowDiff> compare(
            List<Map<String, String>> previousRows,
            List<Map<String, String>> currentRows,
            Map<String, DbfColumnType> columnTypes
    ) {
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
}
