package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.comparison.domain.DiffService;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for comparing CSV files and generating row-level diffs.
 * Uses a direct sorted merge-join algorithm for memory-efficient comparison.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Parse both CSV files into sorted row lists (normalized, no serialization back to string)</li>
 *   <li>Merge-join the two sorted lists with two pointers to find ADDED and DELETED rows</li>
 *   <li>Post-process adjacent DELETE+INSERT pairs to detect MODIFIED rows</li>
 * </ol>
 *
 * <h2>Interpretation of Merge-Join Output</h2>
 * <ul>
 *   <li>Row only in current → ADDED (INSERT)</li>
 *   <li>Row only in previous → DELETED (DELETE)</li>
 *   <li>Adjacent DELETED + ADDED with SOME unchanged columns → MODIFIED (UPDATE)</li>
 *   <li>Adjacent DELETED + ADDED with ALL columns changed → separate DELETE + INSERT</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li>Sorting: O(n log n) where n = number of rows</li>
 *   <li>Merge-join: O(n) single pass</li>
 *   <li>Post-processing: O(D) where D = number of differences</li>
 * </ul>
 * <p><strong>Memory:</strong> ~2x file size (parsed rows only, no string re-serialization)</p>
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

    private final DiffService diffService;
    private final ObjectMapper objectMapper;

    public CsvDiffService(DiffService diffService, ObjectMapper objectMapper) {
        this.diffService = diffService;
        this.objectMapper = objectMapper;
    }

    /**
     * Compares two sets of pre-parsed CSV rows and returns the differences.
     *
     * <p>Accepts pre-parsed rows (e.g., from streaming S3 parsing) instead of
     * raw CSV strings, avoiding the full-string-in-memory step. Sorts rows
     * in-place before comparison for memory efficiency.</p>
     *
     * @param previousRows Pre-parsed rows from previous batch (null or empty for first batch)
     * @param currentRows  Pre-parsed rows from current batch (null or empty for deletion detection)
     * @param headers      Column names from the CSV header row
     * @return List of row differences (added, modified, deleted)
     * @throws InvalidCsvHeaderException if column headers are invalid
     */
    public List<CsvRowDiff> compare(
            List<List<String>> previousRows,
            List<List<String>> currentRows,
            List<String> headers
    ) {
        validateHeaders(headers);

        List<List<String>> prev = (previousRows != null) ? previousRows : Collections.emptyList();
        List<List<String>> curr = (currentRows != null) ? currentRows : Collections.emptyList();

        if (prev.isEmpty() && curr.isEmpty()) {
            log.debug("Both row lists are empty, no changes");
            return Collections.emptyList();
        }

        log.debug("Comparing pre-parsed CSV rows: previousRows={}, currentRows={}, headers={}",
                prev.size(), curr.size(), headers.size());

        // Sort mutable lists in-place for memory efficiency
        if (!prev.isEmpty()) {
            prev.sort(CsvDiffService::compareRows);
        }
        if (!curr.isEmpty()) {
            curr.sort(CsvDiffService::compareRows);
        }

        return compareSorted(prev, curr, headers);
    }

    /**
     * Compares two CSV file contents and returns the differences.
     *
     * <p>Uses a direct sorted merge-join algorithm instead of text-based Myers diff,
     * reducing memory usage from ~6x to ~2x per file.</p>
     *
     * @param previousCsvContent Raw CSV content from previous batch (empty string for first batch)
     * @param currentCsvContent  Raw CSV content from current batch
     * @param headers            Column names from the CSV header row
     * @return List of row differences (added, modified, deleted)
     * @throws InvalidCsvHeaderException if column headers are invalid
     */
    public List<CsvRowDiff> compare(
            String previousCsvContent,
            String currentCsvContent,
            List<String> headers
    ) {
        // Validate headers
        validateHeaders(headers);

        // Handle edge case: both empty → no changes
        boolean currentEmpty = currentCsvContent == null || currentCsvContent.isBlank();
        boolean previousEmpty = previousCsvContent == null || previousCsvContent.isBlank();

        if (currentEmpty && previousEmpty) {
            log.debug("Both CSV contents are empty, no changes");
            return Collections.emptyList();
        }

        log.debug("Comparing CSV files: previousLength={}, currentLength={}, headers={}",
                previousEmpty ? 0 : previousCsvContent.length(),
                currentEmpty ? 0 : currentCsvContent.length(),
                headers.size());

        try {
            // Step 1: Parse and sort both CSV files into row lists
            List<List<String>> previousRows = parseCsvToSortedRows(previousCsvContent, headers);
            List<List<String>> currentRows = parseCsvToSortedRows(currentCsvContent, headers);

            // Step 2: Merge-join comparison with modification detection
            return compareSorted(previousRows, currentRows, headers);

        } catch (IOException e) {
            log.error("Failed to compare CSV files", e);
            throw new CsvDiffException("Failed to compare CSV files: " + e.getMessage(), e);
        }
    }

    /**
     * Parses CSV content into a sorted list of rows.
     * Each row is a list of string values in header order.
     *
     * <p>Normalizes embedded newlines (replaced with spaces) and sorts
     * rows lexicographically by all columns.</p>
     *
     * @param csvContent Raw CSV content (may be null or blank)
     * @param headers    Column names for value extraction
     * @return Sorted list of rows (each row is a list of values in header order)
     */
    private List<List<String>> parseCsvToSortedRows(String csvContent, List<String> headers) throws IOException {
        if (csvContent == null || csvContent.isBlank()) {
            return Collections.emptyList();
        }

        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(new StringReader(csvContent))) {

            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(headers.size());
                for (String header : headers) {
                    String value = record.isMapped(header) ? record.get(header) : "";
                    // Normalize embedded newlines - replace with space
                    if (value != null) {
                        value = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
                    }
                    row.add(value);
                }
                rows.add(row);
            }
        }

        // Sort rows by all columns (lexicographic comparison)
        rows.sort(CsvDiffService::compareRows);

        return rows;
    }

    /**
     * Lexicographic comparator for two CSV rows (lists of string values).
     * Compares column by column; null values are treated as empty strings.
     */
    private static int compareRows(List<String> row1, List<String> row2) {
        int size = Math.min(row1.size(), row2.size());
        for (int i = 0; i < size; i++) {
            String val1 = row1.get(i) != null ? row1.get(i) : "";
            String val2 = row2.get(i) != null ? row2.get(i) : "";
            int cmp = val1.compareTo(val2);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(row1.size(), row2.size());
    }

    /**
     * Performs a merge-join comparison on two sorted row lists and returns differences.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Walk both sorted lists with two pointers</li>
     *   <li>If prev row &lt; curr row → DELETED (advance prev pointer)</li>
     *   <li>If prev row &gt; curr row → ADDED (advance curr pointer)</li>
     *   <li>If prev row == curr row → no change (advance both pointers)</li>
     *   <li>After loop: remaining prev rows → DELETED, remaining curr rows → ADDED</li>
     *   <li>Post-process: adjacent DELETE+INSERT with shared columns → MODIFIED</li>
     * </ol>
     *
     * @param previousRows Sorted rows from previous CSV
     * @param currentRows  Sorted rows from current CSV
     * @param headers      Column names for building row maps
     * @return List of CsvRowDiff representing all changes
     */
    private List<CsvRowDiff> compareSorted(
            List<List<String>> previousRows,
            List<List<String>> currentRows,
            List<String> headers
    ) {
        // Phase 1: Merge-join to produce raw DELETED and ADDED entries
        List<RawDiff> rawDiffs = new ArrayList<>();

        int prevIdx = 0;
        int currIdx = 0;

        while (prevIdx < previousRows.size() && currIdx < currentRows.size()) {
            List<String> prevRow = previousRows.get(prevIdx);
            List<String> currRow = currentRows.get(currIdx);
            int cmp = compareRows(prevRow, currRow);

            if (cmp < 0) {
                // prev row is smaller → it was deleted
                rawDiffs.add(new RawDiff(RawDiffType.DELETED, prevRow));
                prevIdx++;
            } else if (cmp > 0) {
                // curr row is smaller → it was added
                rawDiffs.add(new RawDiff(RawDiffType.ADDED, currRow));
                currIdx++;
            } else {
                // Equal → no change, advance both
                prevIdx++;
                currIdx++;
            }
        }

        // Remaining previous rows are all deleted
        while (prevIdx < previousRows.size()) {
            rawDiffs.add(new RawDiff(RawDiffType.DELETED, previousRows.get(prevIdx)));
            prevIdx++;
        }

        // Remaining current rows are all added
        while (currIdx < currentRows.size()) {
            rawDiffs.add(new RawDiff(RawDiffType.ADDED, currentRows.get(currIdx)));
            currIdx++;
        }

        if (rawDiffs.isEmpty()) {
            return Collections.emptyList();
        }

        // Phase 2: Post-process to detect MODIFIED rows from adjacent DELETE+INSERT pairs
        return postProcessRawDiffs(rawDiffs, headers);
    }

    /**
     * Post-processes raw DELETED/ADDED diffs to detect MODIFIED rows.
     *
     * <p>Adjacent pairs of different types (DELETED+ADDED or ADDED+DELETED) are checked
     * for shared columns. In a sorted merge-join, the ordering depends on which row version
     * sorts first lexicographically, so both orderings must be handled.</p>
     *
     * <ul>
     *   <li>If some columns are unchanged → MODIFIED (UPDATE)</li>
     *   <li>If ALL columns changed → keep as separate DELETE + INSERT</li>
     * </ul>
     *
     * <p>Line numbers are assigned sequentially (1-based) for each diff entry.</p>
     */
    private List<CsvRowDiff> postProcessRawDiffs(List<RawDiff> rawDiffs, List<String> headers) {
        List<CsvRowDiff> result = new ArrayList<>();
        int lineNumber = 1;
        int i = 0;

        while (i < rawDiffs.size()) {
            RawDiff current = rawDiffs.get(i);

            // Check for adjacent pair of different types (potential modification)
            if (i + 1 < rawDiffs.size()) {
                RawDiff next = rawDiffs.get(i + 1);

                if (current.type != next.type) {
                    // Determine which is previous (DELETED) and which is current (ADDED)
                    List<String> prevValues = current.type == RawDiffType.DELETED ? current.values : next.values;
                    List<String> currValues = current.type == RawDiffType.ADDED ? current.values : next.values;

                    Map<String, String> previousRow = rowToMap(prevValues, headers);
                    Map<String, String> currentRow = rowToMap(currValues, headers);
                    Map<String, String> changedColumns = findChangedColumns(previousRow, currentRow);

                    if (!changedColumns.isEmpty() && changedColumns.size() < headers.size()) {
                        // True modification: some columns unchanged, some changed
                        result.add(CsvRowDiff.modified(lineNumber++, currentRow, changedColumns));
                        i += 2;
                        continue;
                    } else if (changedColumns.size() == headers.size()) {
                        // All columns changed = different rows, treat as DELETE + INSERT
                        result.add(CsvRowDiff.deleted(lineNumber++, previousRow));
                        result.add(CsvRowDiff.added(lineNumber++, currentRow));
                        i += 2;
                        continue;
                    } else {
                        // No changes (shouldn't happen from merge-join), skip both
                        i += 2;
                        continue;
                    }
                }
            }

            // Standalone DELETED or ADDED (no adjacent pair of different type)
            Map<String, String> row = rowToMap(current.values, headers);
            if (current.type == RawDiffType.DELETED) {
                result.add(CsvRowDiff.deleted(lineNumber++, row));
            } else {
                result.add(CsvRowDiff.added(lineNumber++, row));
            }
            i++;
        }

        log.debug("Merge-join produced {} diffs", result.size());
        return result;
    }

    /**
     * Converts a row (list of values) to a map of column name to value.
     */
    private Map<String, String> rowToMap(List<String> row, List<String> headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size() && i < row.size(); i++) {
            map.put(headers.get(i), row.get(i));
        }
        return map;
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
     * Raw diff type used internally during merge-join before post-processing.
     */
    private enum RawDiffType {
        ADDED, DELETED
    }

    /**
     * Internal representation of a raw diff entry before post-processing.
     */
    private record RawDiff(RawDiffType type, List<String> values) {}

    /**
     * Validates CSV headers for security and sanity.
     */
    private void validateHeaders(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return; // Empty is valid for first batch
        }

        // Check column count
        if (headers.size() > MAX_COLUMNS) {
            log.error("CSV has too many columns: count={}, max={}", headers.size(), MAX_COLUMNS);
            throw new InvalidCsvHeaderException(
                    "CSV file has " + headers.size() + " columns, exceeding limit of " + MAX_COLUMNS);
        }

        // Validate each column name
        List<String> invalidColumns = new ArrayList<>();
        for (String column : headers) {
            if (column == null || column.trim().isEmpty()) {
                invalidColumns.add("<empty>");
                continue;
            }

            if (!VALID_COLUMN_NAME.matcher(column).matches()) {
                invalidColumns.add(sanitizeForLogging(column));
            }
        }

        if (!invalidColumns.isEmpty()) {
            log.error("CSV has invalid column names: columns={}", String.join(", ", invalidColumns));
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

    /**
     * Exception thrown when CSV diff operation fails.
     */
    public static class CsvDiffException extends RuntimeException {
        public CsvDiffException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
