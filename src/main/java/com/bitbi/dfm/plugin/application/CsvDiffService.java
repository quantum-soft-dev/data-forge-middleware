package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.DiffService;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for comparing CSV files and generating row-level diffs.
 * Uses the existing DiffService (java-diff-utils with Myers algorithm) for comparison.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Sort both CSV files by all columns to normalize row order</li>
 *   <li>Generate diff using DiffService (Myers algorithm)</li>
 *   <li>Parse diff output to identify ADDED, REMOVED, and MODIFIED rows</li>
 * </ol>
 *
 * <h2>Interpretation of Diff Output</h2>
 * <ul>
 *   <li>ADDED only → new row, generates INSERT</li>
 *   <li>REMOVED only → deleted row, generates DELETE</li>
 *   <li>Adjacent REMOVED + ADDED at same position → modified row, generates UPDATE</li>
 * </ul>
 *
 * @see DiffService
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
     * Compares two CSV file contents and returns the differences.
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

        if (currentCsvContent == null || currentCsvContent.isBlank()) {
            log.debug("Current CSV content is empty");
            return Collections.emptyList();
        }

        log.debug("Comparing CSV files: previousLength={}, currentLength={}, headers={}",
                previousCsvContent != null ? previousCsvContent.length() : 0,
                currentCsvContent.length(),
                headers.size());

        try {
            // Step 1: Sort both CSV files by all columns
            String sortedPrevious = sortCsvContent(previousCsvContent, headers);
            String sortedCurrent = sortCsvContent(currentCsvContent, headers);

            // Step 2: Generate diff using existing DiffService (Myers algorithm)
            DiffService.DiffResult diffResult = diffService.generateDiff(
                    sortedCurrent,
                    sortedPrevious,
                    "current.csv",
                    "previous.csv"
            );

            log.debug("Diff result: changeType={}, additions={}, deletions={}",
                    diffResult.changeType(),
                    diffResult.lineAdditions(),
                    diffResult.lineDeletions());

            // If unchanged, return empty list
            if (diffResult.changeType() == ChangeType.UNCHANGED) {
                return Collections.emptyList();
            }

            // Step 3: Parse diff JSON and convert to CsvRowDiff
            return parseDiffJson(diffResult.unifiedDiffJson(), headers);

        } catch (IOException e) {
            log.error("Failed to compare CSV files", e);
            throw new CsvDiffException("Failed to compare CSV files: " + e.getMessage(), e);
        }
    }

    /**
     * Sorts CSV content by all columns to normalize row order.
     * This ensures consistent comparison regardless of how the client exports data.
     *
     * <p>Note: The output does NOT include headers - only data rows are returned.
     * This is important because the diff algorithm treats every line as data.</p>
     */
    private String sortCsvContent(String csvContent, List<String> headers) throws IOException {
        if (csvContent == null || csvContent.isBlank()) {
            return "";
        }

        // Parse CSV content
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(new StringReader(csvContent))) {

            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>();
                for (String header : headers) {
                    row.add(record.isMapped(header) ? record.get(header) : "");
                }
                rows.add(row);
            }
        }

        // Sort rows by all columns (lexicographic comparison)
        rows.sort((row1, row2) -> {
            for (int i = 0; i < row1.size(); i++) {
                String val1 = row1.get(i) != null ? row1.get(i) : "";
                String val2 = row2.get(i) != null ? row2.get(i) : "";
                int cmp = val1.compareTo(val2);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        });

        // Write sorted rows (NO header) - each row on its own line
        // Using simple comma-join for consistent diff comparison
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            // Use CSVPrinter for proper escaping of values with commas/quotes
            StringWriter rowWriter = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(rowWriter, CSVFormat.DEFAULT)) {
                printer.printRecord(row);
            }
            // Remove trailing newline from individual row
            String rowStr = rowWriter.toString();
            if (rowStr.endsWith("\r\n")) {
                rowStr = rowStr.substring(0, rowStr.length() - 2);
            } else if (rowStr.endsWith("\n")) {
                rowStr = rowStr.substring(0, rowStr.length() - 1);
            }
            result.append(rowStr);
            if (i < rows.size() - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Parses diff JSON and converts to CsvRowDiff objects.
     * Identifies ADDED-only, REMOVED-only, and MODIFIED (adjacent REMOVED+ADDED) changes.
     *
     * <p>The algorithm processes changes in order, detecting modifications as
     * adjacent REMOVED+ADDED pairs. Non-adjacent REMOVED becomes DELETED,
     * and non-adjacent ADDED becomes ADDED (INSERT).</p>
     */
    private List<CsvRowDiff> parseDiffJson(String diffJson, List<String> headers) throws JsonProcessingException {
        if (diffJson == null || diffJson.isBlank()) {
            return Collections.emptyList();
        }

        List<CsvRowDiff> diffs = new ArrayList<>();
        JsonNode root = objectMapper.readTree(diffJson);
        JsonNode hunks = root.get("hunks");

        if (hunks == null || !hunks.isArray()) {
            return Collections.emptyList();
        }

        for (JsonNode hunk : hunks) {
            JsonNode changes = hunk.get("changes");
            if (changes == null || !changes.isArray()) {
                continue;
            }

            // Process changes in order to detect adjacent REMOVED+ADDED pairs (modifications)
            int i = 0;
            int changesSize = changes.size();

            while (i < changesSize) {
                JsonNode change = changes.get(i);
                String type = change.get("type").asText();

                if ("REMOVED".equals(type)) {
                    // Check if next change is ADDED (adjacent = modification)
                    if (i + 1 < changesSize) {
                        JsonNode nextChange = changes.get(i + 1);
                        String nextType = nextChange.get("type").asText();

                        if ("ADDED".equals(nextType)) {
                            // Check if this is a true modification or unrelated delete+insert
                            int lineNumber = nextChange.get("lineNumber").asInt();
                            String previousContent = change.get("content").asText();
                            String currentContent = nextChange.get("content").asText();

                            Map<String, String> previousRow = parseCsvLine(previousContent, headers);
                            Map<String, String> currentRow = parseCsvLine(currentContent, headers);

                            Map<String, String> changedColumns = findChangedColumns(previousRow, currentRow);

                            // If ALL columns changed, these are different rows (delete + insert)
                            // If SOME columns are unchanged, it's a modification of the same row
                            if (!changedColumns.isEmpty() && changedColumns.size() < headers.size()) {
                                // True modification: some columns unchanged, some changed
                                diffs.add(CsvRowDiff.modified(lineNumber, currentRow, changedColumns));
                                i += 2; // Skip both REMOVED and ADDED
                                continue;
                            } else if (changedColumns.size() == headers.size()) {
                                // All columns changed = different rows, treat as DELETE + INSERT
                                // Fall through to process REMOVED as DELETE
                            } else {
                                // No changes (shouldn't happen in diff), skip both
                                i += 2;
                                continue;
                            }
                        }
                    }

                    // Standalone REMOVED = DELETED
                    int lineNumber = change.get("lineNumber").asInt();
                    String content = change.get("content").asText();
                    Map<String, String> row = parseCsvLine(content, headers);
                    diffs.add(CsvRowDiff.deleted(lineNumber, row));
                    i++;

                } else if ("ADDED".equals(type)) {
                    // Standalone ADDED = INSERT
                    int lineNumber = change.get("lineNumber").asInt();
                    String content = change.get("content").asText();
                    Map<String, String> row = parseCsvLine(content, headers);
                    diffs.add(CsvRowDiff.added(lineNumber, row));
                    i++;

                } else {
                    // Skip unknown change types
                    i++;
                }
            }
        }

        log.debug("Parsed {} diffs from diff JSON", diffs.size());
        return diffs;
    }

    /**
     * Parses a CSV line into a map of column name to value.
     */
    private Map<String, String> parseCsvLine(String csvLine, List<String> headers) {
        Map<String, String> row = new LinkedHashMap<>();

        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(csvLine))) {
            for (CSVRecord record : parser) {
                for (int i = 0; i < headers.size() && i < record.size(); i++) {
                    row.put(headers.get(i), record.get(i));
                }
                break; // Only process first record
            }
        } catch (IOException e) {
            log.warn("Failed to parse CSV line: {}", csvLine, e);
            // Fallback: split by comma (simple case)
            String[] values = csvLine.split(",", -1);
            for (int i = 0; i < headers.size() && i < values.length; i++) {
                row.put(headers.get(i), values[i]);
            }
        }

        return row;
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
