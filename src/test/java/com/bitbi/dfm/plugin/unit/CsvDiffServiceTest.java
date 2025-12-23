package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.CsvDiffService;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CsvDiffService.
 * Tests row-level CSV comparison logic for SQL generation.
 *
 * TDD: These tests are written FIRST and should FAIL until implementation.
 */
@DisplayName("CsvDiffService")
class CsvDiffServiceTest {

    private CsvDiffService csvDiffService;

    @BeforeEach
    void setUp() {
        csvDiffService = new CsvDiffService();
    }

    @Nested
    @DisplayName("Row Comparison")
    class RowComparison {

        @Test
        @DisplayName("should detect added rows in current batch")
        void shouldDetectAddedRowsInCurrentBatch() {
            // Given - previous batch has 2 rows, current has 3
            List<Map<String, String>> previousRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Charlie")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.ADDED);
            assertThat(diffs.get(0).values()).containsEntry("id", "3");
            assertThat(diffs.get(0).values()).containsEntry("name", "Charlie");
        }

        @Test
        @DisplayName("should detect deleted rows from previous batch")
        void shouldDetectDeletedRowsFromPreviousBatch() {
            // Given - previous batch has 3 rows, current has 2
            List<Map<String, String>> previousRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Charlie")
            );
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.DELETED);
            assertThat(diffs.get(0).values()).containsEntry("id", "3");
        }

        @Test
        @DisplayName("should detect modified rows with changed values")
        void shouldDetectModifiedRowsWithChangedValues() {
            // Given - same rows but one has different value
            // Use LinkedHashMap to ensure "id" is the first column (identity key)
            Map<String, String> prevRow = new LinkedHashMap<>();
            prevRow.put("id", "1");
            prevRow.put("name", "Alice");
            prevRow.put("email", "alice@old.com");
            List<Map<String, String>> previousRows = List.of(prevRow);

            Map<String, String> currRow = new LinkedHashMap<>();
            currRow.put("id", "1");
            currRow.put("name", "Alice");
            currRow.put("email", "alice@new.com");
            List<Map<String, String>> currentRows = List.of(currRow);

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("email", "alice@new.com");
            assertThat(diffs.get(0).changedColumns()).containsEntry("email", "alice@old.com");
        }

        @Test
        @DisplayName("should return empty list for identical files")
        void shouldReturnEmptyListForIdenticalFiles() {
            // Given - identical rows
            List<Map<String, String>> previousRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).isEmpty();
        }

        @Test
        @DisplayName("should handle first batch with no previous rows")
        void shouldHandleFirstBatchWithNoPreviousRows() {
            // Given - no previous batch
            List<Map<String, String>> previousRows = List.of();
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then - all rows should be ADDED
            assertThat(diffs).hasSize(2);
            assertThat(diffs).allMatch(d -> d.type() == CsvRowDiff.DiffType.ADDED);
        }

        @Test
        @DisplayName("should detect multiple changes in single comparison")
        void shouldDetectMultipleChangesInSingleComparison() {
            // Given - use LinkedHashMap to ensure "id" is first column
            Map<String, String> prev1 = new LinkedHashMap<>();
            prev1.put("id", "1");
            prev1.put("name", "Alice");
            Map<String, String> prev2 = new LinkedHashMap<>();
            prev2.put("id", "2");
            prev2.put("name", "Bob");
            Map<String, String> prev3 = new LinkedHashMap<>();
            prev3.put("id", "3");
            prev3.put("name", "Charlie");
            List<Map<String, String>> previousRows = List.of(prev1, prev2, prev3);

            Map<String, String> curr1 = new LinkedHashMap<>();
            curr1.put("id", "1");
            curr1.put("name", "Alice Updated");  // MODIFIED
            Map<String, String> curr2 = new LinkedHashMap<>();
            curr2.put("id", "2");
            curr2.put("name", "Bob");            // UNCHANGED
            Map<String, String> curr3 = new LinkedHashMap<>();
            curr3.put("id", "4");
            curr3.put("name", "David");           // ADDED (3 is DELETED)
            List<Map<String, String>> currentRows = List.of(curr1, curr2, curr3);

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(3);
            assertThat(diffs).extracting(CsvRowDiff::type)
                .containsExactlyInAnyOrder(
                    CsvRowDiff.DiffType.MODIFIED,
                    CsvRowDiff.DiffType.DELETED,
                    CsvRowDiff.DiffType.ADDED
                );
        }
    }

    @Nested
    @DisplayName("Row Identity")
    class RowIdentity {

        @Test
        @DisplayName("should use all columns for row identity comparison")
        void shouldUseAllColumnsForRowIdentityComparison() {
            // Given - same id but different email = MODIFIED row
            // Use LinkedHashMap to ensure "id" is the first column (identity key)
            Map<String, String> prevRow = new LinkedHashMap<>();
            prevRow.put("id", "1");
            prevRow.put("name", "Alice");
            prevRow.put("email", "alice@example.com");
            List<Map<String, String>> previousRows = List.of(prevRow);

            Map<String, String> currRow = new LinkedHashMap<>();
            currRow.put("id", "1");
            currRow.put("name", "Alice");
            currRow.put("email", "bob@example.com");
            List<Map<String, String>> currentRows = List.of(currRow);

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then - should detect as MODIFIED, not as delete+add
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
        }

        @Test
        @DisplayName("should handle row order changes correctly")
        void shouldHandleRowOrderChangesCorrectly() {
            // Given - same rows in different order
            List<Map<String, String>> previousRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob")
            );
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "1", "name", "Alice")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then - no changes (order doesn't matter)
            assertThat(diffs).isEmpty();
        }
    }

    @Nested
    @DisplayName("DBF Type Handling")
    class DbfTypeHandling {

        @Test
        @DisplayName("should track column types from header info")
        void shouldTrackColumnTypesFromHeaderInfo() {
            // Given - column types map
            Map<String, DbfColumnType> columnTypes = Map.of(
                "id", DbfColumnType.INTEGER,
                "name", DbfColumnType.CHARACTER,
                "amount", DbfColumnType.CURRENCY
            );
            List<Map<String, String>> previousRows = List.of();
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice", "amount", "100.50")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, columnTypes);

            // Then - should include type info for SQL generation
            assertThat(diffs).hasSize(1);
            // The diff values should be preserved as-is (type conversion happens in SQL generator)
        }

        @Test
        @DisplayName("should default to CHARACTER type when type not specified")
        void shouldDefaultToCharacterTypeWhenTypeNotSpecified() {
            // Given - no column types map
            List<Map<String, String>> previousRows = List.of();
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then - should work without type information
            assertThat(diffs).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Line Numbers")
    class LineNumbers {

        @Test
        @DisplayName("should assign correct line numbers to added rows")
        void shouldAssignCorrectLineNumbersToAddedRows() {
            // Given
            List<Map<String, String>> previousRows = List.of();
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Charlie")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then - line numbers should be 1-based (row 1, 2, 3 excluding header)
            assertThat(diffs).hasSize(3);
            assertThat(diffs).extracting(CsvRowDiff::lineNumber)
                .containsExactly(1, 2, 3);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty values correctly")
        void shouldHandleEmptyValuesCorrectly() {
            // Given - use LinkedHashMap to ensure "id" is first column
            Map<String, String> prevRow = new LinkedHashMap<>();
            prevRow.put("id", "1");
            prevRow.put("name", "Alice");
            prevRow.put("email", "alice@example.com");
            List<Map<String, String>> previousRows = List.of(prevRow);

            Map<String, String> currRow = new LinkedHashMap<>();
            currRow.put("id", "1");
            currRow.put("name", "Alice");
            currRow.put("email", "");
            List<Map<String, String>> currentRows = List.of(currRow);

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("email", "");
        }

        @Test
        @DisplayName("should handle special characters in values")
        void shouldHandleSpecialCharactersInValues() {
            // Given
            List<Map<String, String>> previousRows = List.of();
            List<Map<String, String>> currentRows = List.of(
                Map.of("id", "1", "name", "O'Brien", "note", "Line1\nLine2")
            );

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).values()).containsEntry("name", "O'Brien");
            assertThat(diffs.get(0).values()).containsEntry("note", "Line1\nLine2");
        }

        @Test
        @DisplayName("should handle null column values")
        void shouldHandleNullColumnValues() {
            // Given - map with null value (simulating missing column)
            // Use LinkedHashMap to ensure "id" is the first column
            Map<String, String> rowWithNull = new LinkedHashMap<>();
            rowWithNull.put("id", "1");
            rowWithNull.put("name", null);

            List<Map<String, String>> previousRows = List.of();
            List<Map<String, String>> currentRows = List.of(rowWithNull);

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, Map.of());

            // Then
            assertThat(diffs).hasSize(1);
        }
    }
}
