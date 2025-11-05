package com.bitbi.dfm.comparison.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for FileComparison aggregate root.
 * Tests domain logic and business rules.
 *
 * Test Coverage:
 * - T050: shouldUpdateStatisticsWhenAddingResult
 */
@DisplayName("FileComparison Aggregate Unit Tests")
class FileComparisonTest {

    /**
     * T050: Unit test for FileComparison.addResult updating statistics
     * Verifies that adding comparison results correctly updates the aggregate statistics.
     */
    @Test
    @DisplayName("should update statistics when adding comparison result")
    void shouldUpdateStatisticsWhenAddingResult() {
        // Given: A new comparison in PENDING status
        FileComparison comparison = new FileComparison(
            UUID.randomUUID(), // currentBatchId
            UUID.randomUUID(), // targetBatchId
            UUID.randomUUID()  // accountId
        );

        // Verify initial state
        assertThat(comparison.getStatus()).isEqualTo(ComparisonStatus.PENDING);
        assertThat(comparison.getTotalFilesCompared()).isZero();
        assertThat(comparison.getFilesChanged()).isZero();
        assertThat(comparison.getFilesAdded()).isZero();
        assertThat(comparison.getFilesUnchanged()).isZero();
        assertThat(comparison.getTotalChangeSize()).isZero();

        // When: Adding a MODIFIED result
        comparison.startComparison(); // Transition to IN_PROGRESS
        ComparisonResult modifiedResult = new ComparisonResult(
            1L,  // comparisonId
            UUID.randomUUID(),  // fileId
            UUID.randomUUID(),  // targetFileId
            ChangeType.MODIFIED,
            "{\"diff\":\"some diff\"}", // unifiedDiffJson
            25,    // lineAdditions
            10,    // lineDeletions
            1500L  // changeSize
        );
        comparison.addResult(modifiedResult);

        // Then: Statistics should be updated for MODIFIED file
        assertThat(comparison.getTotalFilesCompared()).isEqualTo(1);
        assertThat(comparison.getFilesChanged()).isEqualTo(1);
        assertThat(comparison.getFilesAdded()).isZero();
        assertThat(comparison.getFilesUnchanged()).isZero();
        assertThat(comparison.getTotalChangeSize()).isEqualTo(1500L);

        // When: Adding an ADDED result
        ComparisonResult addedResult = new ComparisonResult(
            1L,  // comparisonId
            UUID.randomUUID(),  // fileId
            null,  // targetFileId (new file)
            ChangeType.ADDED,
            "{\"diff\":\"new file diff\"}", // unifiedDiffJson
            100,   // lineAdditions
            0,     // lineDeletions
            5000L  // changeSize
        );
        comparison.addResult(addedResult);

        // Then: Statistics should include both results
        assertThat(comparison.getTotalFilesCompared()).isEqualTo(2);
        assertThat(comparison.getFilesChanged()).isEqualTo(1);
        assertThat(comparison.getFilesAdded()).isEqualTo(1);
        assertThat(comparison.getFilesUnchanged()).isZero();
        assertThat(comparison.getTotalChangeSize()).isEqualTo(6500L); // 1500 + 5000

        // When: Adding an UNCHANGED result
        ComparisonResult unchangedResult = new ComparisonResult(
            1L,  // comparisonId
            UUID.randomUUID(),  // fileId
            UUID.randomUUID(),  // targetFileId
            ChangeType.UNCHANGED,
            null,  // unifiedDiffJson (no diff for unchanged)
            0,     // lineAdditions
            0,     // lineDeletions
            0L     // changeSize
        );
        comparison.addResult(unchangedResult);

        // Then: Statistics should include all three results
        assertThat(comparison.getTotalFilesCompared()).isEqualTo(3);
        assertThat(comparison.getFilesChanged()).isEqualTo(1);
        assertThat(comparison.getFilesAdded()).isEqualTo(1);
        assertThat(comparison.getFilesUnchanged()).isEqualTo(1);
        assertThat(comparison.getTotalChangeSize()).isEqualTo(6500L); // Unchanged adds 0

        // Verify invariant: total = changed + added + unchanged
        assertThat(comparison.getTotalFilesCompared())
            .isEqualTo(comparison.getFilesChanged() + comparison.getFilesAdded() + comparison.getFilesUnchanged());
    }

    /**
     * Additional test: Can add results in PENDING or IN_PROGRESS status
     */
    @Test
    @DisplayName("should allow adding result in PENDING status")
    void shouldAllowAddingResultInPendingStatus() {
        // Given: A comparison in PENDING status
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        ComparisonResult result = new ComparisonResult(
            1L, UUID.randomUUID(), UUID.randomUUID(), ChangeType.MODIFIED, "{}", 10, 5, 100L
        );

        // When: Adding result in PENDING status
        comparison.addResult(result);

        // Then: Should succeed (no exception)
        assertThat(comparison.getTotalFilesCompared()).isEqualTo(1);
    }

    /**
     * Additional test: Cannot add results when status is COMPLETED
     */
    @Test
    @DisplayName("should throw exception when adding result in COMPLETED status")
    void shouldThrowExceptionWhenAddingResultInCompletedStatus() {
        // Given: A completed comparison
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        comparison.startComparison();
        comparison.completeComparison();

        ComparisonResult result = new ComparisonResult(
            1L, UUID.randomUUID(), UUID.randomUUID(), ChangeType.MODIFIED, "{}", 10, 5, 100L
        );

        // When & Then: Should throw IllegalStateException
        assertThatThrownBy(() -> comparison.addResult(result))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPLETED");
    }

    /**
     * Additional test: Cannot add results when status is FAILED
     */
    @Test
    @DisplayName("should throw exception when adding result in FAILED status")
    void shouldThrowExceptionWhenAddingResultInFailedStatus() {
        // Given: A failed comparison
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        comparison.startComparison();
        comparison.failComparison("Some error occurred");

        ComparisonResult result = new ComparisonResult(
            1L, UUID.randomUUID(), UUID.randomUUID(), ChangeType.MODIFIED, "{}", 10, 5, 100L
        );

        // When & Then: Should throw IllegalStateException
        assertThatThrownBy(() -> comparison.addResult(result))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FAILED");
    }

    /**
     * Additional test: State transitions work correctly
     */
    @Test
    @DisplayName("should transition states correctly")
    void shouldTransitionStatesCorrectly() {
        // Given: A new comparison
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(comparison.getStatus()).isEqualTo(ComparisonStatus.PENDING);
        assertThat(comparison.getStartedAt()).isNull();
        assertThat(comparison.getCompletedAt()).isNull();

        // When: Starting comparison
        comparison.startComparison();

        // Then: Status should be IN_PROGRESS with startedAt timestamp
        assertThat(comparison.getStatus()).isEqualTo(ComparisonStatus.IN_PROGRESS);
        assertThat(comparison.getStartedAt()).isNotNull();
        assertThat(comparison.getCompletedAt()).isNull();

        // When: Completing comparison
        comparison.completeComparison();

        // Then: Status should be COMPLETED with completedAt timestamp
        assertThat(comparison.getStatus()).isEqualTo(ComparisonStatus.COMPLETED);
        assertThat(comparison.getStartedAt()).isNotNull();
        assertThat(comparison.getCompletedAt()).isNotNull();
        assertThat(comparison.getCompletedAt()).isAfterOrEqualTo(comparison.getStartedAt());
    }

    /**
     * Additional test: Failing comparison sets error message
     */
    @Test
    @DisplayName("should set error message when failing comparison")
    void shouldSetErrorMessageWhenFailingComparison() {
        // Given: A comparison in progress
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        comparison.startComparison();

        // When: Failing with error message
        String errorMessage = "S3 access denied for file xyz.csv";
        comparison.failComparison(errorMessage);

        // Then: Status should be FAILED with error message
        assertThat(comparison.getStatus()).isEqualTo(ComparisonStatus.FAILED);
        assertThat(comparison.getErrorMessage()).isEqualTo(errorMessage);
        assertThat(comparison.getCompletedAt()).isNotNull();
    }

    /**
     * Additional test: canDelete() logic
     */
    @Test
    @DisplayName("should not allow delete when in IN_PROGRESS status")
    void shouldNotAllowDeleteWhenInProgress() {
        // Given: A comparison in progress
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        comparison.startComparison();

        // When & Then: canDelete() should return false
        assertThat(comparison.canDelete()).isFalse();
    }

    /**
     * Additional test: canDelete() allows deletion for COMPLETED
     */
    @Test
    @DisplayName("should allow delete when COMPLETED")
    void shouldAllowDeleteWhenCompleted() {
        // Given: A completed comparison
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        comparison.startComparison();
        comparison.completeComparison();

        // When & Then: canDelete() should return true
        assertThat(comparison.canDelete()).isTrue();
    }

    /**
     * Additional test: canDelete() allows deletion for FAILED
     */
    @Test
    @DisplayName("should allow delete when FAILED")
    void shouldAllowDeleteWhenFailed() {
        // Given: A failed comparison
        FileComparison comparison = new FileComparison(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        comparison.startComparison();
        comparison.failComparison("Error");

        // When & Then: canDelete() should return true
        assertThat(comparison.canDelete()).isTrue();
    }

    /**
     * Additional test: getSummary() generates correct value object
     */
    @Test
    @DisplayName("should generate correct summary value object")
    void shouldGenerateCorrectSummary() {
        // Given: A comparison with results
        UUID currentBatchId = UUID.randomUUID();
        UUID targetBatchId = UUID.randomUUID();
        FileComparison comparison = new FileComparison(currentBatchId, targetBatchId, UUID.randomUUID());
        comparison.startComparison();

        comparison.addResult(new ComparisonResult(1L, UUID.randomUUID(), UUID.randomUUID(), ChangeType.MODIFIED, "{}", 10, 5, 100L));
        comparison.addResult(new ComparisonResult(1L, UUID.randomUUID(), null, ChangeType.ADDED, "{}", 20, 0, 200L));
        comparison.addResult(new ComparisonResult(1L, UUID.randomUUID(), UUID.randomUUID(), ChangeType.UNCHANGED, null, 0, 0, 0L));

        comparison.completeComparison();

        // When: Getting summary
        ComparisonSummary summary = comparison.getSummary();

        // Then: Summary should reflect aggregate statistics
        assertThat(summary).isNotNull();
        assertThat(summary.totalFilesCompared()).isEqualTo(3);
        assertThat(summary.filesChanged()).isEqualTo(1);
        assertThat(summary.filesAdded()).isEqualTo(1);
        assertThat(summary.filesUnchanged()).isEqualTo(1);
        assertThat(summary.totalChangeSize()).isEqualTo(300L);
        assertThat(summary.currentBatchId()).isEqualTo(currentBatchId);
        assertThat(summary.targetBatchId()).isEqualTo(targetBatchId);
        assertThat(summary.comparisonTimestamp()).isEqualTo(comparison.getCompletedAt());
    }
}
