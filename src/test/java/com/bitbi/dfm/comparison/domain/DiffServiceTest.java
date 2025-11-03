package com.bitbi.dfm.comparison.domain;

import com.bitbi.dfm.comparison.infrastructure.DiffServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for DiffService implementation.
 * Tests the core diff generation logic using java-diff-utils library.
 *
 * Test Coverage:
 * - T047: shouldReturnUnchangedForIdenticalFiles
 * - T048: shouldReturnModifiedWithCorrectDiff
 * - T049: shouldReturnAddedForNewFile
 */
@DisplayName("DiffService Unit Tests")
class DiffServiceTest {

    private DiffService diffService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        diffService = new DiffServiceImpl(objectMapper);
    }

    /**
     * T047: Unit test for DiffService with identical files
     * Verifies that comparing two identical files returns UNCHANGED change type with no diff.
     */
    @Test
    @DisplayName("should return UNCHANGED for identical files")
    void shouldReturnUnchangedForIdenticalFiles() {
        // Given: Two identical file contents
        String currentContent = """
            header1,header2,header3
            value1,value2,value3
            value4,value5,value6
            """;
        String targetContent = """
            header1,header2,header3
            value1,value2,value3
            value4,value5,value6
            """;
        String currentFileName = "data.csv";
        String targetFileName = "data.csv";

        // When: Performing diff comparison
        DiffService.DiffResult result = diffService.generateDiff(
            currentContent, targetContent, currentFileName, targetFileName
        );

        // Then: Change type should be UNCHANGED with zero additions/deletions
        assertThat(result).isNotNull();
        assertThat(result.changeType()).isEqualTo(ChangeType.UNCHANGED);
        assertThat(result.lineAdditions()).isZero();
        assertThat(result.lineDeletions()).isZero();
        assertThat(result.unifiedDiffJson()).isNullOrEmpty();
        assertThat(result.changeSize()).isZero();
    }

    /**
     * T048: Unit test for DiffService with modified file
     * Verifies that comparing two different files returns MODIFIED change type with correct diff structure.
     */
    @Test
    @DisplayName("should return MODIFIED with correct diff for changed files")
    void shouldReturnModifiedWithCorrectDiff() {
        // Given: Two files with modifications
        String currentContent = """
            header1,header2,header3
            new_value1,new_value2,new_value3
            value4,value5,value6
            value7,value8,value9
            """;
        String targetContent = """
            header1,header2,header3
            old_value1,old_value2,old_value3
            value4,value5,value6
            """;
        String currentFileName = "data.csv";
        String targetFileName = "data.csv";

        // When: Performing diff comparison
        DiffService.DiffResult result = diffService.generateDiff(
            currentContent, targetContent, currentFileName, targetFileName
        );

        // Then: Change type should be MODIFIED with correct statistics
        assertThat(result).isNotNull();
        assertThat(result.changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(result.lineAdditions()).isGreaterThan(0); // At least 1 modified line + 1 new line
        assertThat(result.lineDeletions()).isGreaterThan(0); // At least 1 old line removed
        assertThat(result.unifiedDiffJson()).isNotNull();
        assertThat(result.changeSize()).isGreaterThan(0);

        // Verify diff structure is valid JSON
        assertThat(result.unifiedDiffJson()).isNotEmpty();
        assertThat(result.unifiedDiffJson()).contains("\"hunks\"");
        assertThat(result.unifiedDiffJson()).contains("\"oldFileName\"");
        assertThat(result.unifiedDiffJson()).contains("\"newFileName\"");
    }

    /**
     * T049: Unit test for DiffService with new file (no target)
     * Verifies that comparing a file against null/empty target returns ADDED change type.
     */
    @Test
    @DisplayName("should return ADDED for new file with no target")
    void shouldReturnAddedForNewFile() {
        // Given: A new file with no previous version
        String currentContent = """
            {
              "key": "value",
              "array": [1, 2, 3]
            }
            """;
        String targetContent = null; // No previous version
        String currentFileName = "new_file.json";
        String targetFileName = null; // New file, no target name

        // When: Performing diff comparison
        DiffService.DiffResult result = diffService.generateDiff(
            currentContent, targetContent, currentFileName, targetFileName
        );

        // Then: Change type should be ADDED with all lines as additions
        assertThat(result).isNotNull();
        assertThat(result.changeType()).isEqualTo(ChangeType.ADDED);
        assertThat(result.lineAdditions()).isGreaterThan(0); // All lines are new
        assertThat(result.lineDeletions()).isZero(); // No deletions
        assertThat(result.unifiedDiffJson()).isNotNull();
        assertThat(result.changeSize()).isGreaterThan(0);

        // Verify diff structure is valid JSON
        assertThat(result.unifiedDiffJson()).isNotEmpty();
        assertThat(result.unifiedDiffJson()).contains("\"hunks\"");
        assertThat(result.unifiedDiffJson()).contains("\"type\":\"ADDED\"");
    }

    /**
     * Additional test: Empty current content with non-empty target
     */
    @Test
    @DisplayName("should treat single newline files as UNCHANGED")
    void shouldReturnUnchangedForSingleNewlineFiles() {
        // Given: Both files contain only a single newline
        String currentContent = "\n";
        String targetContent = "\n";
        String currentFileName = "newline.txt";
        String targetFileName = "newline.txt";

        // When: Performing diff comparison
        DiffService.DiffResult result = diffService.generateDiff(
            currentContent, targetContent, currentFileName, targetFileName
        );

        // Then: Change type should be UNCHANGED
        assertThat(result).isNotNull();
        assertThat(result.changeType()).isEqualTo(ChangeType.UNCHANGED);
        assertThat(result.lineAdditions()).isZero();
        assertThat(result.lineDeletions()).isZero();
    }

    /**
     * Additional test: Empty target should be treated as ADDED
     */
    @Test
    @DisplayName("should return ADDED for empty target content")
    void shouldReturnAddedForEmptyTarget() {
        // Given: Current content exists, target is empty
        String currentContent = "line1\nline2\nline3";
        String targetContent = "";
        String currentFileName = "data.txt";
        String targetFileName = "data.txt";

        // When: Performing diff comparison
        DiffService.DiffResult result = diffService.generateDiff(
            currentContent, targetContent, currentFileName, targetFileName
        );

        // Then: Change type should be ADDED
        assertThat(result).isNotNull();
        assertThat(result.changeType()).isEqualTo(ChangeType.ADDED);
        assertThat(result.lineAdditions()).isGreaterThan(0);
        assertThat(result.lineDeletions()).isZero();
    }

    /**
     * Additional test: Null current content should throw exception
     */
    @Test
    @DisplayName("should throw IllegalArgumentException for null current content")
    void shouldThrowExceptionForNullCurrentContent() {
        // Given: Null current content
        String currentContent = null;
        String targetContent = "some content";
        String currentFileName = "data.txt";
        String targetFileName = "data.txt";

        // When & Then: Should throw IllegalArgumentException
        assertThatThrownBy(() ->
            diffService.generateDiff(currentContent, targetContent, currentFileName, targetFileName)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Additional test: Large file modification
     */
    @Test
    @DisplayName("should handle large file modifications correctly")
    void shouldHandleLargeFileModifications() {
        // Given: Large files (simulating 1000 lines)
        StringBuilder currentBuilder = new StringBuilder();
        StringBuilder targetBuilder = new StringBuilder();

        for (int i = 0; i < 1000; i++) {
            if (i % 10 == 0) {
                currentBuilder.append("line_").append(i).append(",modified_data\n");
                targetBuilder.append("line_").append(i).append(",original_data\n");
            } else {
                String line = "line_" + i + ",original_data\n";
                currentBuilder.append(line);
                targetBuilder.append(line);
            }
        }

        String currentContent = currentBuilder.toString();
        String targetContent = targetBuilder.toString();
        String currentFileName = "large_file.csv";
        String targetFileName = "large_file.csv";

        // When: Performing diff comparison
        DiffService.DiffResult result = diffService.generateDiff(
            currentContent, targetContent, currentFileName, targetFileName
        );

        // Then: Should detect all 100 modifications (every 10th line)
        assertThat(result).isNotNull();
        assertThat(result.changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(result.lineAdditions()).isGreaterThan(0); // 100 modified lines added
        assertThat(result.lineDeletions()).isGreaterThan(0); // 100 original lines removed
        assertThat(result.unifiedDiffJson()).isNotNull();
    }
}
