package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.DiffService;
import com.bitbi.dfm.plugin.application.CsvDiffService;
import com.bitbi.dfm.plugin.domain.CsvRowDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CsvDiffService.
 * Tests row-level CSV comparison logic using DiffService.
 */
@DisplayName("CsvDiffService")
@ExtendWith(MockitoExtension.class)
class CsvDiffServiceTest {

    @Mock
    private DiffService diffService;

    private CsvDiffService csvDiffService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        csvDiffService = new CsvDiffService(diffService, objectMapper);
    }

    @Nested
    @DisplayName("Row Comparison")
    class RowComparison {

        @Test
        @DisplayName("should detect added rows in current batch")
        void shouldDetectAddedRowsInCurrentBatch() {
            // Given - previous batch has 2 rows, current has 3
            String previousCsv = "id,name\n1,Alice\n2,Bob";
            String currentCsv = "id,name\n1,Alice\n2,Bob\n3,Charlie";
            List<String> headers = List.of("id", "name");

            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 3, "oldLines": 0,
                    "newStart": 3, "newLines": 1,
                    "changes": [
                      {"type": "ADDED", "lineNumber": 3, "content": "3,Charlie"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.MODIFIED, diffJson, 1, 0, 10L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

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
            String previousCsv = "id,name\n1,Alice\n2,Bob\n3,Charlie";
            String currentCsv = "id,name\n1,Alice\n2,Bob";
            List<String> headers = List.of("id", "name");

            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 3, "oldLines": 1,
                    "newStart": 3, "newLines": 0,
                    "changes": [
                      {"type": "REMOVED", "lineNumber": 3, "content": "3,Charlie"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.MODIFIED, diffJson, 0, 1, 10L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.DELETED);
            assertThat(diffs.get(0).values()).containsEntry("id", "3");
        }

        @Test
        @DisplayName("should detect modified rows with changed values")
        void shouldDetectModifiedRowsWithChangedValues() {
            // Given - same row but different email
            String previousCsv = "id,name,email\n1,Alice,alice@old.com";
            String currentCsv = "id,name,email\n1,Alice,alice@new.com";
            List<String> headers = List.of("id", "name", "email");

            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 1, "oldLines": 1,
                    "newStart": 1, "newLines": 1,
                    "changes": [
                      {"type": "REMOVED", "lineNumber": 1, "content": "1,Alice,alice@old.com"},
                      {"type": "ADDED", "lineNumber": 1, "content": "1,Alice,alice@new.com"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.MODIFIED, diffJson, 1, 1, 20L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

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
            String previousCsv = "id,name\n1,Alice\n2,Bob";
            String currentCsv = "id,name\n1,Alice\n2,Bob";
            List<String> headers = List.of("id", "name");

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.UNCHANGED, null, 0, 0, 0L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then
            assertThat(diffs).isEmpty();
        }

        @Test
        @DisplayName("should handle first batch with no previous content")
        void shouldHandleFirstBatchWithNoPreviousContent() {
            // Given - no previous batch
            String previousCsv = "";
            String currentCsv = "id,name\n1,Alice\n2,Bob";
            List<String> headers = List.of("id", "name");

            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 1, "oldLines": 0,
                    "newStart": 1, "newLines": 2,
                    "changes": [
                      {"type": "ADDED", "lineNumber": 1, "content": "1,Alice"},
                      {"type": "ADDED", "lineNumber": 2, "content": "2,Bob"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.ADDED, diffJson, 2, 0, 20L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then - all rows should be ADDED
            assertThat(diffs).hasSize(2);
            assertThat(diffs).allMatch(d -> d.type() == CsvRowDiff.DiffType.ADDED);
        }

        @Test
        @DisplayName("should detect multiple changes in single comparison")
        void shouldDetectMultipleChangesInSingleComparison() {
            // Given
            String previousCsv = "id,name\n1,Alice\n2,Bob\n3,Charlie";
            String currentCsv = "id,name\n1,Alice Updated\n2,Bob\n4,David";
            List<String> headers = List.of("id", "name");

            // Diff output: first pair (1,Alice→1,Alice Updated) shares 'id' column → MODIFIED
            // Second pair (3,Charlie→4,David) has ALL columns changed → DELETE + INSERT
            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 1, "oldLines": 3,
                    "newStart": 1, "newLines": 3,
                    "changes": [
                      {"type": "REMOVED", "lineNumber": 1, "content": "1,Alice"},
                      {"type": "ADDED", "lineNumber": 1, "content": "1,Alice Updated"},
                      {"type": "REMOVED", "lineNumber": 3, "content": "3,Charlie"},
                      {"type": "ADDED", "lineNumber": 3, "content": "4,David"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.MODIFIED, diffJson, 2, 2, 40L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then - first pair = MODIFIED (id unchanged), second pair = DELETE + INSERT (all columns changed)
            assertThat(diffs).hasSize(3);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.MODIFIED).count()).isEqualTo(1);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).count()).isEqualTo(1);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Sorting Before Comparison")
    class SortingBeforeComparison {

        @Test
        @DisplayName("should sort rows before comparison to normalize order")
        void shouldSortRowsBeforeComparisonToNormalizeOrder() {
            // Given - same rows in different order
            String previousCsv = "id,name\n2,Bob\n1,Alice";
            String currentCsv = "id,name\n1,Alice\n2,Bob";
            List<String> headers = List.of("id", "name");

            // After sorting, both should be identical
            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.UNCHANGED, null, 0, 0, 0L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then - no changes (order doesn't matter after sorting)
            assertThat(diffs).isEmpty();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should detect deletions when current content is empty")
        void shouldDetectDeletionsWhenCurrentContentIsEmpty() {
            // Given - previous has data, current is empty (all rows deleted)
            String previousCsv = "id,name\n1,Alice";
            String currentCsv = "";
            List<String> headers = List.of("id", "name");

            // When current is empty, all previous rows should be detected as DELETED
            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 1, "oldLines": 1,
                    "newStart": 1, "newLines": 0,
                    "changes": [
                      {"type": "REMOVED", "lineNumber": 1, "content": "1,Alice"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.MODIFIED, diffJson, 0, 1, 10L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then - all rows should be marked as DELETED (fixes "empty CSV hides deletions" bug)
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.DELETED);
            assertThat(diffs.get(0).values()).containsEntry("id", "1");
            assertThat(diffs.get(0).values()).containsEntry("name", "Alice");
        }

        @Test
        @DisplayName("should handle values with commas")
        void shouldHandleValuesWithCommas() {
            // Given
            String previousCsv = "";
            String currentCsv = "id,name,note\n1,Alice,\"Hello, World\"";
            List<String> headers = List.of("id", "name", "note");

            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 1, "oldLines": 0,
                    "newStart": 1, "newLines": 1,
                    "changes": [
                      {"type": "ADDED", "lineNumber": 1, "content": "1,Alice,\\"Hello, World\\""}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.ADDED, diffJson, 1, 0, 20L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then
            assertThat(diffs).hasSize(1);
        }

        @Test
        @DisplayName("should handle empty values")
        void shouldHandleEmptyValues() {
            // Given
            String previousCsv = "id,name,email\n1,Alice,alice@example.com";
            String currentCsv = "id,name,email\n1,Alice,";
            List<String> headers = List.of("id", "name", "email");

            String diffJson = """
                {
                  "hunks": [{
                    "oldStart": 1, "oldLines": 1,
                    "newStart": 1, "newLines": 1,
                    "changes": [
                      {"type": "REMOVED", "lineNumber": 1, "content": "1,Alice,alice@example.com"},
                      {"type": "ADDED", "lineNumber": 1, "content": "1,Alice,"}
                    ]
                  }]
                }
                """;

            when(diffService.generateDiff(any(), any(), any(), any()))
                .thenReturn(new DiffService.DiffResult(ChangeType.MODIFIED, diffJson, 1, 1, 20L));

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("email", "");
        }
    }
}
