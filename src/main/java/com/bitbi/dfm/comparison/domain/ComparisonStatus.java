package com.bitbi.dfm.comparison.domain;

/**
 * Represents the lifecycle state of a file comparison operation.
 *
 * <p>State transitions:
 * <pre>
 * PENDING → IN_PROGRESS → COMPLETED
 *                       ↘ FAILED
 * </pre>
 *
 * <p>Terminal states (no further transitions):
 * <ul>
 *   <li>COMPLETED - Comparison finished successfully</li>
 *   <li>FAILED - Comparison failed due to error</li>
 * </ul>
 */
public enum ComparisonStatus {
    /**
     * Comparison has been created but processing has not yet started.
     */
    PENDING,

    /**
     * Comparison is actively processing files.
     */
    IN_PROGRESS,

    /**
     * Comparison finished successfully with results available.
     */
    COMPLETED,

    /**
     * Comparison failed due to an error (e.g., S3 access denied, file not found).
     */
    FAILED;

    /**
     * Checks if this status can transition to the target status.
     *
     * @param target the target status to transition to
     * @return true if the transition is valid, false otherwise
     */
    public boolean canTransitionTo(ComparisonStatus target) {
        return switch (this) {
            case PENDING -> target == IN_PROGRESS;
            case IN_PROGRESS -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false; // Terminal states
        };
    }

    /**
     * Checks if this status represents a terminal state (no further transitions possible).
     *
     * @return true if this is a terminal status (COMPLETED or FAILED)
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * Checks if this status allows deletion.
     * Comparisons cannot be deleted while IN_PROGRESS.
     *
     * @return true if deletion is allowed
     */
    public boolean allowsDeletion() {
        return this != IN_PROGRESS;
    }
}
