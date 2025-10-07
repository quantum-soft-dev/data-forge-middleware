package com.bitbi.dfm.batch.domain;

/**
 * Batch lifecycle status enumeration.
 * <p>
 * Defines valid states for a batch upload session with specific transition rules.
 * </p>
 *
 * <h3>State Transitions:</h3>
 * <pre>
 * IN_PROGRESS → COMPLETED     (client calls /complete)
 * IN_PROGRESS → FAILED         (client calls /fail)
 * IN_PROGRESS → CANCELLED      (client calls /cancel)
 * IN_PROGRESS → NOT_COMPLETED  (timeout scheduler)
 * </pre>
 *
 * <p>Terminal states (COMPLETED, FAILED, CANCELLED, NOT_COMPLETED) are immutable.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum BatchStatus {
    /**
     * Active upload session - only one per site allowed.
     * Files can be uploaded in this state.
     */
    IN_PROGRESS,

    /**
     * Successfully finished by client.
     * Terminal state - no further uploads or transitions allowed.
     */
    COMPLETED,

    /**
     * Expired via timeout without explicit completion.
     * Terminal state - batch abandoned due to inactivity.
     */
    NOT_COMPLETED,

    /**
     * Marked as failed by client due to critical error.
     * Terminal state - indicates processing failure.
     */
    FAILED,

    /**
     * Cancelled by client.
     * Terminal state - uploaded files remain in S3.
     */
    CANCELLED;

    /**
     * Check if this status is terminal (no further transitions allowed).
     *
     * @return true if status is COMPLETED, FAILED, CANCELLED, or NOT_COMPLETED
     */
    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }

    /**
     * Check if file uploads are allowed in this status.
     *
     * @return true only if status is IN_PROGRESS
     */
    public boolean allowsFileUpload() {
        return this == IN_PROGRESS;
    }

    /**
     * Validate transition from this status to target status.
     * <p>
     * Explicit state machine validation enforcing allowed transitions only:
     * - IN_PROGRESS → COMPLETED
     * - IN_PROGRESS → NOT_COMPLETED
     * - IN_PROGRESS → FAILED
     * - IN_PROGRESS → CANCELLED
     * All other transitions are forbidden.
     * </p>
     *
     * @param targetStatus the desired target status
     * @throws IllegalStateException if transition is not allowed
     */
    public void validateTransition(BatchStatus targetStatus) {
        // Check if attempting to transition from terminal state
        if (this.isTerminal()) {
            throw new IllegalStateException(
                    String.format("Cannot transition from terminal status %s to %s", this, targetStatus)
            );
        }

        // Check if attempting to stay in same state
        if (this == targetStatus) {
            throw new IllegalStateException(
                    String.format("Invalid transition: already in status %s", this)
            );
        }

        // Explicit state machine validation for IN_PROGRESS
        if (this == IN_PROGRESS) {
            boolean isValidTransition = targetStatus == COMPLETED ||
                                       targetStatus == NOT_COMPLETED ||
                                       targetStatus == FAILED ||
                                       targetStatus == CANCELLED;

            if (!isValidTransition) {
                throw new IllegalStateException(
                        String.format("Invalid transition from %s to %s. " +
                                     "Allowed: COMPLETED, NOT_COMPLETED, FAILED, CANCELLED",
                                     this, targetStatus)
                );
            }
        }
    }
}
