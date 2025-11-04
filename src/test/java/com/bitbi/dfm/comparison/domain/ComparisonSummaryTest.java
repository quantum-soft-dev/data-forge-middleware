package com.bitbi.dfm.comparison.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ComparisonSummary value object.
 * Tests the from() factory method and business methods.
 *
 * Test Coverage:
 * - T079: shouldCreateFromFileComparison
 * - Additional: null validation, invariant validation, helper methods
 */
@DisplayName("ComparisonSummary Value Object Unit Tests")
class ComparisonSummaryTest {

    /**
     * T079: Unit test for ComparisonSummary.from(comparison)
     * Verifies that ComparisonSummary is correctly created from a FileComparison aggregate.
     */
    @Test
    @DisplayName("should create ComparisonSummary from FileComparison aggregate")
    void shouldCreateFromFileComparison() {
        // Given: A completed comparison with statistics
        UUID currentBatchId = UUID.randomUUID();
        UUID targetBatchId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        FileComparison comparison = new FileComparison(currentBatchId, targetBatchId, accountId);
        comparison.startComparison(); // IN_PROGRESS

        // Simulate adding results to update statistics
        // Add MODIFIED result
        ComparisonResult modifiedResult = new ComparisonResult(
            1L,
            UUID.randomUUID(), // fileId
            UUID.randomUUID(), // targetFileId
            ChangeType.MODIFIED,
            "{\"hunks\":[]}",
            10, // lineAdditions
            5,  // lineDeletions
            500L // changeSize
        );
        comparison.addResult(modifiedResult);

        // Add ADDED result
        ComparisonResult addedResult = new ComparisonResult(
            1L,
            UUID.randomUUID(), // fileId
            null, // targetFileId (new file)
            ChangeType.ADDED,
            "{\"hunks\":[]}",
            20, // lineAdditions
            0,  // lineDeletions
            300L // changeSize
        );
        comparison.addResult(addedResult);

        // Add UNCHANGED result
        ComparisonResult unchangedResult = new ComparisonResult(
            1L,
            UUID.randomUUID(), // fileId
            UUID.randomUUID(), // targetFileId
            ChangeType.UNCHANGED,
            null, // No diff for unchanged files
            0,    // lineAdditions
            0,    // lineDeletions
            0L    // changeSize
        );
        comparison.addResult(unchangedResult);

        comparison.completeComparison(); // COMPLETED

        // When: Creating ComparisonSummary from FileComparison
        ComparisonSummary summary = ComparisonSummary.from(comparison);

        // Then: Summary should match comparison statistics
        assertThat(summary).isNotNull();
        assertThat(summary.totalFilesCompared()).isEqualTo(3);
        assertThat(summary.filesChanged()).isEqualTo(1);
        assertThat(summary.filesAdded()).isEqualTo(1);
        assertThat(summary.filesUnchanged()).isEqualTo(1);
        assertThat(summary.totalChangeSize()).isEqualTo(800L); // 500 + 300
        assertThat(summary.currentBatchId()).isEqualTo(currentBatchId);
        assertThat(summary.targetBatchId()).isEqualTo(targetBatchId);
        assertThat(summary.comparisonTimestamp()).isEqualTo(comparison.getCompletedAt());
    }

    @Test
    @DisplayName("should use createdAt timestamp when comparison is not completed")
    void shouldUseCreatedAtWhenNotCompleted() {
        // Given: A comparison in PENDING status (not yet completed)
        UUID currentBatchId = UUID.randomUUID();
        UUID targetBatchId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        FileComparison comparison = new FileComparison(currentBatchId, targetBatchId, accountId);

        // When: Creating ComparisonSummary from pending comparison
        ComparisonSummary summary = ComparisonSummary.from(comparison);

        // Then: Should use createdAt as timestamp (completedAt is null)
        assertThat(summary.comparisonTimestamp()).isEqualTo(comparison.getCreatedAt());
        assertThat(summary.comparisonTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when comparison is null")
    void shouldThrowExceptionWhenComparisonIsNull() {
        // When/Then: Creating ComparisonSummary from null should throw
        assertThatThrownBy(() -> ComparisonSummary.from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FileComparison cannot be null");
    }

    @Test
    @DisplayName("should validate invariant that total equals sum of changed, added, and unchanged")
    void shouldValidateInvariant() {
        // Given: A valid comparison summary
        ComparisonSummary validSummary = new ComparisonSummary(
                10, // total
                5,  // changed
                3,  // added
                2,  // unchanged (5+3+2=10, valid)
                1000L,
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        // When/Then: Validation should pass
        validSummary.validateInvariant(); // Should not throw

        // Given: An invalid comparison summary
        ComparisonSummary invalidSummary = new ComparisonSummary(
                10, // total
                5,  // changed
                3,  // added
                1,  // unchanged (5+3+1=9, invalid!)
                1000L,
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        // When/Then: Validation should fail
        assertThatThrownBy(invalidSummary::validateInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ComparisonSummary invariant violated")
                .hasMessageContaining("totalFilesCompared (10)")
                .hasMessageContaining("filesChanged (5)")
                .hasMessageContaining("filesAdded (3)")
                .hasMessageContaining("filesUnchanged (1)");
    }

    @Test
    @DisplayName("should correctly identify when comparison has changes")
    void shouldIdentifyHasChanges() {
        // Given: Summary with changes (filesChanged > 0)
        ComparisonSummary withChanges = new ComparisonSummary(
                10, 5, 0, 5, 1000L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(withChanges.hasChanges()).isTrue();

        // Given: Summary with added files (filesAdded > 0)
        ComparisonSummary withAdditions = new ComparisonSummary(
                10, 0, 5, 5, 1000L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(withAdditions.hasChanges()).isTrue();

        // Given: Summary with both changed and added files
        ComparisonSummary withBoth = new ComparisonSummary(
                10, 3, 2, 5, 1000L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(withBoth.hasChanges()).isTrue();

        // Given: Summary with no changes (only unchanged files)
        ComparisonSummary noChanges = new ComparisonSummary(
                10, 0, 0, 10, 0L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(noChanges.hasChanges()).isFalse();
    }

    @Test
    @DisplayName("should calculate change percentage correctly")
    void shouldCalculateChangePercentage() {
        // Given: 50% changed (5 out of 10)
        ComparisonSummary halfChanged = new ComparisonSummary(
                10, 3, 2, 5, 1000L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(halfChanged.getChangePercentage()).isEqualTo(50.0);

        // Given: 100% changed
        ComparisonSummary allChanged = new ComparisonSummary(
                10, 7, 3, 0, 1000L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(allChanged.getChangePercentage()).isEqualTo(100.0);

        // Given: 0% changed
        ComparisonSummary noneChanged = new ComparisonSummary(
                10, 0, 0, 10, 0L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(noneChanged.getChangePercentage()).isEqualTo(0.0);

        // Given: 0 files compared (edge case)
        ComparisonSummary noFiles = new ComparisonSummary(
                0, 0, 0, 0, 0L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(noFiles.getChangePercentage()).isEqualTo(0.0);

        // Given: 33.33% changed (1 out of 3)
        ComparisonSummary thirdChanged = new ComparisonSummary(
                3, 1, 0, 2, 500L, Instant.now(), UUID.randomUUID(), UUID.randomUUID()
        );
        assertThat(thirdChanged.getChangePercentage()).isCloseTo(33.33, org.assertj.core.data.Offset.offset(0.01));
    }
}
