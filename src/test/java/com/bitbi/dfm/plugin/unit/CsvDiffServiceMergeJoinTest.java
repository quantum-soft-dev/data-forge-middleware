package com.bitbi.dfm.plugin.unit;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests for the merge-join algorithm in CsvDiffService.
 * Verifies that CsvDiffService performs direct sorted merge-join comparison
 * without delegating to DiffService (Myers diff).
 */
@DisplayName("CsvDiffService - Merge Join Algorithm")
@ExtendWith(MockitoExtension.class)
class CsvDiffServiceMergeJoinTest {

    @Mock
    private DiffService diffService;

    private CsvDiffService csvDiffService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        csvDiffService = new CsvDiffService(diffService, objectMapper);
    }

    @Nested
    @DisplayName("Basic Correctness")
    class BasicCorrectness {

        @Test
        @DisplayName("should return empty list when both files are empty")
        void shouldReturnEmptyListWhenBothFilesEmpty() {
            // Given
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare((String) null, (String) null, headers);

            // Then
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should return empty list when both files are blank")
        void shouldReturnEmptyListWhenBothFilesBlank() {
            // Given
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare("", "", headers);

            // Then
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should return empty list when files are identical")
        void shouldReturnEmptyListWhenFilesIdentical() {
            // Given
            String csv = "id,name\n1,Alice\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(csv, csv, headers);

            // Then
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect all rows as added when previous is empty")
        void shouldDetectAllRowsAsAddedWhenPreviousEmpty() {
            // Given
            String previous = "";
            String current = "id,name\n1,Alice\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(2);
            assertThat(diffs).allMatch(d -> d.type() == CsvRowDiff.DiffType.ADDED);
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect all rows as deleted when current is empty")
        void shouldDetectAllRowsAsDeletedWhenCurrentEmpty() {
            // Given
            String previous = "id,name\n1,Alice\n2,Bob\n";
            String current = "";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(2);
            assertThat(diffs).allMatch(d -> d.type() == CsvRowDiff.DiffType.DELETED);
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect added rows when rows are only in current file")
        void shouldDetectAddedRows() {
            // Given
            String previous = "name,age\nAlice,30\n";
            String current = "name,age\nAlice,30\nBob,25\n";
            List<String> headers = List.of("name", "age");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.ADDED);
            assertThat(diffs.get(0).values()).containsEntry("name", "Bob");
            assertThat(diffs.get(0).values()).containsEntry("age", "25");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect deleted rows when rows are only in previous file")
        void shouldDetectDeletedRows() {
            // Given
            String previous = "name,age\nAlice,30\nBob,25\n";
            String current = "name,age\nAlice,30\n";
            List<String> headers = List.of("name", "age");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.DELETED);
            assertThat(diffs.get(0).values()).containsEntry("name", "Bob");
            assertThat(diffs.get(0).values()).containsEntry("age", "25");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect modified rows when some columns changed")
        void shouldDetectModifiedRows() {
            // Given - Alice's email changed, id and name stayed the same
            String previous = "id,name,email\n1,Alice,alice@old.com\n";
            String current = "id,name,email\n1,Alice,alice@new.com\n";
            List<String> headers = List.of("id", "name", "email");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("id", "1");
            assertThat(diffs.get(0).values()).containsEntry("name", "Alice");
            assertThat(diffs.get(0).values()).containsEntry("email", "alice@new.com");
            assertThat(diffs.get(0).changedColumns()).containsEntry("email", "alice@old.com");
            assertThat(diffs.get(0).changedColumns()).doesNotContainKey("id");
            assertThat(diffs.get(0).changedColumns()).doesNotContainKey("name");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should treat all columns changed as delete and insert")
        void shouldTreatAllColumnsChangedAsDeleteAndInsert() {
            // Given - all columns differ between adjacent rows
            String previous = "id,name\n1,Alice\n";
            String current = "id,name\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then - all columns changed, so it's DELETE + INSERT, not MODIFIED
            assertThat(diffs).hasSize(2);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).count()).isEqualTo(1);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).count()).isEqualTo(1);

            CsvRowDiff deleted = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).findFirst().orElseThrow();
            assertThat(deleted.values()).containsEntry("id", "1");
            assertThat(deleted.values()).containsEntry("name", "Alice");

            CsvRowDiff added = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).findFirst().orElseThrow();
            assertThat(added.values()).containsEntry("id", "2");
            assertThat(added.values()).containsEntry("name", "Bob");
            verifyNoInteractions(diffService);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle single row file")
        void shouldHandleSingleRowFile() {
            // Given
            String previous = "id,name\n1,Alice\n";
            String current = "id,name\n1,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then - id unchanged, name changed -> MODIFIED
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("id", "1");
            assertThat(diffs.get(0).values()).containsEntry("name", "Bob");
            assertThat(diffs.get(0).changedColumns()).containsEntry("name", "Alice");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle duplicate rows with no changes")
        void shouldHandleDuplicateRows() {
            // Given - identical rows in both files including duplicates
            String previous = "id,name\n1,Alice\n1,Alice\n2,Bob\n";
            String current = "id,name\n1,Alice\n1,Alice\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then - no changes
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should normalize embedded newlines in CSV values")
        void shouldNormalizeEmbeddedNewlines() {
            // Given - CSV with embedded newlines in quoted fields
            String previous = "";
            String current = "id,name,memo\n1,Alice,\"Line1\nLine2\"\n";
            List<String> headers = List.of("id", "name", "memo");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then - should parse successfully and normalize newlines to spaces
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.ADDED);
            assertThat(diffs.get(0).values()).containsEntry("memo", "Line1 Line2");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle mixed changes - adds, deletes, and modifications")
        void shouldHandleMixedChanges() {
            // Given
            String previous = "id,name,email\n1,Alice,alice@test.com\n2,Bob,bob@test.com\n3,Charlie,charlie@test.com\n";
            String current = "id,name,email\n1,Alice,alice@new.com\n3,Charlie,charlie@test.com\n4,David,david@test.com\n";
            List<String> headers = List.of("id", "name", "email");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then:
            // - Alice's email changed (id and name unchanged) -> MODIFIED
            // - Bob was removed -> DELETED
            // - Charlie unchanged -> no diff
            // - David was added -> ADDED
            assertThat(diffs).hasSize(3);

            // Check MODIFIED
            List<CsvRowDiff> modified = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.MODIFIED).toList();
            assertThat(modified).hasSize(1);
            assertThat(modified.get(0).values()).containsEntry("id", "1");
            assertThat(modified.get(0).values()).containsEntry("email", "alice@new.com");
            assertThat(modified.get(0).changedColumns()).containsEntry("email", "alice@test.com");

            // Check DELETED
            List<CsvRowDiff> deleted = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).toList();
            assertThat(deleted).hasSize(1);
            assertThat(deleted.get(0).values()).containsEntry("id", "2");
            assertThat(deleted.get(0).values()).containsEntry("name", "Bob");

            // Check ADDED
            List<CsvRowDiff> added = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).toList();
            assertThat(added).hasSize(1);
            assertThat(added.get(0).values()).containsEntry("id", "4");
            assertThat(added.get(0).values()).containsEntry("name", "David");
            verifyNoInteractions(diffService);
        }
    }

    @Nested
    @DisplayName("Sorting Behavior")
    class SortingBehavior {

        @Test
        @DisplayName("should sort rows before comparison to normalize order")
        void shouldSortRowsBeforeComparison() {
            // Given - same rows in different order
            String previous = "id,name\n2,Bob\n1,Alice\n";
            String current = "id,name\n1,Alice\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then - no changes since after sorting they're identical
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect changes correctly regardless of row order")
        void shouldDetectChangesCorrectlyRegardlessOfRowOrder() {
            // Given - rows in different order with a change
            String previous = "id,name\n3,Charlie\n1,Alice\n2,Bob\n";
            String current = "id,name\n2,Bob\n4,David\n1,Alice\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then - Charlie deleted, David added
            assertThat(diffs).hasSize(2);

            List<CsvRowDiff> deleted = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).toList();
            assertThat(deleted).hasSize(1);
            assertThat(deleted.get(0).values()).containsEntry("name", "Charlie");

            List<CsvRowDiff> added = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).toList();
            assertThat(added).hasSize(1);
            assertThat(added.get(0).values()).containsEntry("name", "David");
            verifyNoInteractions(diffService);
        }
    }

    @Nested
    @DisplayName("DiffService Not Called")
    class DiffServiceNotCalled {

        @Test
        @DisplayName("should not call DiffService for any comparison")
        void shouldNotCallDiffServiceForAnyComparison() {
            // Given - a normal comparison scenario
            String previous = "id,name\n1,Alice\n";
            String current = "id,name\n1,Alice\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            csvDiffService.compare(previous, current, headers);

            // Then - DiffService should never be called
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should not call DiffService when previous is null")
        void shouldNotCallDiffServiceWhenPreviousNull() {
            // Given
            String current = "id,name\n1,Alice\n";
            List<String> headers = List.of("id", "name");

            // When
            csvDiffService.compare(null, current, headers);

            // Then
            verifyNoInteractions(diffService);
        }
    }

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("should produce same results as old algorithm for realistic dataset")
        void shouldProduceSameResultsAsOldAlgorithm() {
            // Given - a realistic dataset with various change types
            String previous = "id,name,email,age\n"
                    + "1,Alice,alice@test.com,30\n"
                    + "2,Bob,bob@test.com,25\n"
                    + "3,Charlie,charlie@test.com,35\n"
                    + "4,David,david@test.com,28\n"
                    + "5,Eve,eve@test.com,32\n";

            String current = "id,name,email,age\n"
                    + "1,Alice,alice@new.com,30\n"       // MODIFIED (email changed)
                    + "2,Bob,bob@test.com,25\n"          // UNCHANGED
                    + "3,Charlie,charlie@test.com,36\n"  // MODIFIED (age changed)
                    + "5,Eve,eve@test.com,32\n"          // UNCHANGED
                    + "6,Frank,frank@test.com,40\n";     // ADDED

            List<String> headers = List.of("id", "name", "email", "age");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            // David (id=4) was DELETED
            // Alice (id=1) was MODIFIED (email changed)
            // Charlie (id=3) was MODIFIED (age changed)
            // Frank (id=6) was ADDED
            assertThat(diffs).hasSize(4);

            // Check modifications
            List<CsvRowDiff> modified = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.MODIFIED).toList();
            assertThat(modified).hasSize(2);

            CsvRowDiff aliceModified = modified.stream()
                    .filter(d -> "1".equals(d.values().get("id"))).findFirst().orElseThrow();
            assertThat(aliceModified.values()).containsEntry("email", "alice@new.com");
            assertThat(aliceModified.changedColumns()).containsEntry("email", "alice@test.com");
            assertThat(aliceModified.changedColumns()).doesNotContainKey("id");
            assertThat(aliceModified.changedColumns()).doesNotContainKey("name");
            assertThat(aliceModified.changedColumns()).doesNotContainKey("age");

            CsvRowDiff charlieModified = modified.stream()
                    .filter(d -> "3".equals(d.values().get("id"))).findFirst().orElseThrow();
            assertThat(charlieModified.values()).containsEntry("age", "36");
            assertThat(charlieModified.changedColumns()).containsEntry("age", "35");

            // Check deletion
            List<CsvRowDiff> deleted = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).toList();
            assertThat(deleted).hasSize(1);
            assertThat(deleted.get(0).values()).containsEntry("id", "4");
            assertThat(deleted.get(0).values()).containsEntry("name", "David");

            // Check addition
            List<CsvRowDiff> added = diffs.stream()
                    .filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).toList();
            assertThat(added).hasSize(1);
            assertThat(added.get(0).values()).containsEntry("id", "6");
            assertThat(added.get(0).values()).containsEntry("name", "Frank");

            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle values with commas in quoted fields")
        void shouldHandleValuesWithCommas() {
            // Given
            String previous = "";
            String current = "id,name,note\n1,Alice,\"Hello, World\"\n";
            List<String> headers = List.of("id", "name", "note");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.ADDED);
            assertThat(diffs.get(0).values()).containsEntry("note", "Hello, World");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle empty column values")
        void shouldHandleEmptyColumnValues() {
            // Given
            String previous = "id,name,email\n1,Alice,alice@example.com\n";
            String current = "id,name,email\n1,Alice,\n";
            List<String> headers = List.of("id", "name", "email");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("email", "");
            assertThat(diffs.get(0).changedColumns()).containsEntry("email", "alice@example.com");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle first batch with no previous content returning all added")
        void shouldHandleFirstBatchWithNoPreviousContent() {
            // Given
            String previous = "";
            String current = "id,name\n1,Alice\n2,Bob\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then
            assertThat(diffs).hasSize(2);
            assertThat(diffs).allMatch(d -> d.type() == CsvRowDiff.DiffType.ADDED);
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect multiple change types in single comparison")
        void shouldDetectMultipleChangeTypesInSingleComparison() {
            // Given - matching the existing test scenario
            String previous = "id,name\n1,Alice\n2,Bob\n3,Charlie\n";
            String current = "id,name\n1,Alice Updated\n2,Bob\n4,David\n";
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previous, current, headers);

            // Then:
            // After sorting:
            //   prev: [1,Alice], [2,Bob], [3,Charlie]
            //   curr: [1,Alice Updated], [2,Bob], [4,David]
            // Merge-join:
            //   [1,Alice] vs [1,Alice Updated]: "1" < "1" false, "1" > "1" false -> compare "Alice" vs "Alice Updated"
            //     "Alice" < "Alice Updated" (lexicographic) -> prev < curr -> DELETED [1,Alice]
            //     then [2,Bob] vs [1,Alice Updated]: "2" > "1" -> ADDED [1,Alice Updated]
            //   ... etc.
            // Post-process: adjacent DELETED [1,Alice] + ADDED [1,Alice Updated] -> check shared columns
            //   "id" changed (1 vs 1) - no, same. "name" changed (Alice vs Alice Updated) - yes.
            //   1 out of 2 columns changed -> MODIFIED
            //
            // [3,Charlie] vs [4,David]: all columns differ -> DELETE + INSERT
            assertThat(diffs).hasSize(3);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.MODIFIED).count()).isEqualTo(1);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.DELETED).count()).isEqualTo(1);
            assertThat(diffs.stream().filter(d -> d.type() == CsvRowDiff.DiffType.ADDED).count()).isEqualTo(1);
            verifyNoInteractions(diffService);
        }
    }

    @Nested
    @DisplayName("Pre-Parsed Rows Overload")
    class PreParsedRowsOverload {

        @Test
        @DisplayName("should detect added rows from pre-parsed row lists")
        void shouldDetectAddedRowsFromPreParsedLists() {
            // Given
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("1", "Alice")));

            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice")));
            currentRows.add(new ArrayList<>(List.of("2", "Bob")));

            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.ADDED);
            assertThat(diffs.get(0).values()).containsEntry("id", "2");
            assertThat(diffs.get(0).values()).containsEntry("name", "Bob");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect deleted rows from pre-parsed row lists")
        void shouldDetectDeletedRowsFromPreParsedLists() {
            // Given
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("1", "Alice")));
            previousRows.add(new ArrayList<>(List.of("2", "Bob")));

            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice")));

            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.DELETED);
            assertThat(diffs.get(0).values()).containsEntry("id", "2");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should detect modifications from pre-parsed row lists")
        void shouldDetectModificationsFromPreParsedLists() {
            // Given - same id, different email
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("1", "Alice", "alice@old.com")));

            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice", "alice@new.com")));

            List<String> headers = List.of("id", "name", "email");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.MODIFIED);
            assertThat(diffs.get(0).values()).containsEntry("email", "alice@new.com");
            assertThat(diffs.get(0).changedColumns()).containsEntry("email", "alice@old.com");
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should return empty list for identical pre-parsed rows")
        void shouldReturnEmptyListForIdenticalPreParsedRows() {
            // Given
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("1", "Alice")));
            previousRows.add(new ArrayList<>(List.of("2", "Bob")));

            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice")));
            currentRows.add(new ArrayList<>(List.of("2", "Bob")));

            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, headers);

            // Then
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle null previous rows as empty (first batch)")
        void shouldHandleNullPreviousRowsAsEmpty() {
            // Given
            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice")));

            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(
                    (List<List<String>>) null, currentRows, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.ADDED);
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should handle null current rows as empty (all deleted)")
        void shouldHandleNullCurrentRowsAsEmpty() {
            // Given
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("1", "Alice")));

            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(
                    previousRows, (List<List<String>>) null, headers);

            // Then
            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).type()).isEqualTo(CsvRowDiff.DiffType.DELETED);
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should return empty when both row lists are null")
        void shouldReturnEmptyWhenBothNull() {
            // Given
            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(
                    (List<List<String>>) null, (List<List<String>>) null, headers);

            // Then
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should sort unsorted rows before comparison")
        void shouldSortUnsortedRowsBeforeComparison() {
            // Given - same rows in different order
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("2", "Bob")));
            previousRows.add(new ArrayList<>(List.of("1", "Alice")));

            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice")));
            currentRows.add(new ArrayList<>(List.of("2", "Bob")));

            List<String> headers = List.of("id", "name");

            // When
            List<CsvRowDiff> diffs = csvDiffService.compare(previousRows, currentRows, headers);

            // Then - no changes after sorting
            assertThat(diffs).isEmpty();
            verifyNoInteractions(diffService);
        }

        @Test
        @DisplayName("should produce same results as string-based compare")
        void shouldProduceSameResultsAsStringBasedCompare() {
            // Given - realistic dataset
            String previousCsv = "id,name,email\n1,Alice,alice@test.com\n2,Bob,bob@test.com\n3,Charlie,charlie@test.com\n";
            String currentCsv = "id,name,email\n1,Alice,alice@new.com\n2,Bob,bob@test.com\n4,David,david@test.com\n";
            List<String> headers = List.of("id", "name", "email");

            // String-based comparison
            List<CsvRowDiff> stringDiffs = csvDiffService.compare(previousCsv, currentCsv, headers);

            // Pre-parsed rows (same data)
            List<List<String>> previousRows = new ArrayList<>();
            previousRows.add(new ArrayList<>(List.of("1", "Alice", "alice@test.com")));
            previousRows.add(new ArrayList<>(List.of("2", "Bob", "bob@test.com")));
            previousRows.add(new ArrayList<>(List.of("3", "Charlie", "charlie@test.com")));

            List<List<String>> currentRows = new ArrayList<>();
            currentRows.add(new ArrayList<>(List.of("1", "Alice", "alice@new.com")));
            currentRows.add(new ArrayList<>(List.of("2", "Bob", "bob@test.com")));
            currentRows.add(new ArrayList<>(List.of("4", "David", "david@test.com")));

            // When
            List<CsvRowDiff> rowDiffs = csvDiffService.compare(previousRows, currentRows, headers);

            // Then - same number and types of diffs
            assertThat(rowDiffs).hasSameSizeAs(stringDiffs);
            for (int i = 0; i < stringDiffs.size(); i++) {
                assertThat(rowDiffs.get(i).type()).isEqualTo(stringDiffs.get(i).type());
                assertThat(rowDiffs.get(i).values()).isEqualTo(stringDiffs.get(i).values());
            }
            verifyNoInteractions(diffService);
        }
    }
}
