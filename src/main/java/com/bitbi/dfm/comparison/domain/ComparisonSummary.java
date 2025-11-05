package com.bitbi.dfm.comparison.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value object representing summary statistics for a file comparison operation.
 *
 * <p>This value object is generated on-demand from a {@link FileComparison} aggregate
 * and is not persisted directly to the database. It provides a convenient way to
 * access comparison statistics for API responses and report generation.
 *
 * <p>Invariant: totalFilesCompared = filesChanged + filesAdded + filesUnchanged
 *
 * @param totalFilesCompared total number of files that were compared
 * @param filesChanged number of files with modifications
 * @param filesAdded number of new files (present in current batch but not in target)
 * @param filesUnchanged number of files with no changes
 * @param totalChangeSize total size of all changes in bytes
 * @param comparisonTimestamp timestamp when the comparison was completed (or created if still in progress)
 * @param currentBatchId ID of the current batch (source)
 * @param targetBatchId ID of the target batch (comparison baseline)
 */
public record ComparisonSummary(
    int totalFilesCompared,
    int filesChanged,
    int filesAdded,
    int filesUnchanged,
    long totalChangeSize,
    Instant comparisonTimestamp,
    UUID currentBatchId,
    UUID targetBatchId
) {
    /**
     * Creates a ComparisonSummary from a FileComparison aggregate.
     *
     * @param comparison the file comparison aggregate
     * @return a new ComparisonSummary value object
     * @throws IllegalArgumentException if comparison is null
     */
    public static ComparisonSummary from(FileComparison comparison) {
        if (comparison == null) {
            throw new IllegalArgumentException("FileComparison cannot be null");
        }

        return new ComparisonSummary(
            comparison.getTotalFilesCompared(),
            comparison.getFilesChanged(),
            comparison.getFilesAdded(),
            comparison.getFilesUnchanged(),
            comparison.getTotalChangeSize(),
            comparison.getCompletedAt() != null ? comparison.getCompletedAt() : comparison.getCreatedAt(),
            comparison.getCurrentBatchId(),
            comparison.getTargetBatchId()
        );
    }

    /**
     * Validates the invariant that total equals the sum of changed, added, and unchanged files.
     *
     * @throws IllegalStateException if the invariant is violated
     */
    public void validateInvariant() {
        if (totalFilesCompared != filesChanged + filesAdded + filesUnchanged) {
            throw new IllegalStateException(
                String.format(
                    "ComparisonSummary invariant violated: totalFilesCompared (%d) != filesChanged (%d) + filesAdded (%d) + filesUnchanged (%d)",
                    totalFilesCompared, filesChanged, filesAdded, filesUnchanged
                )
            );
        }
    }

    /**
     * Checks if there are any changes in this comparison.
     *
     * @return true if there are any changed or added files
     */
    public boolean hasChanges() {
        return filesChanged > 0 || filesAdded > 0;
    }

    /**
     * Gets the percentage of files that were changed or added.
     *
     * @return percentage of changed files (0-100), or 0 if no files were compared
     */
    public double getChangePercentage() {
        if (totalFilesCompared == 0) {
            return 0.0;
        }
        return ((double) (filesChanged + filesAdded) / totalFilesCompared) * 100.0;
    }
}
