package com.bitbi.dfm.batch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for BatchStatus state machine validation.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("BatchStatus State Machine Tests")
class BatchStatusTest {

    @Test
    @DisplayName("Should allow valid transition from IN_PROGRESS to COMPLETED")
    void shouldAllowTransitionToCompleted() {
        // Given
        BatchStatus status = BatchStatus.IN_PROGRESS;

        // When/Then - should not throw
        status.validateTransition(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("Should allow valid transition from IN_PROGRESS to NOT_COMPLETED")
    void shouldAllowTransitionToNotCompleted() {
        // Given
        BatchStatus status = BatchStatus.IN_PROGRESS;

        // When/Then - should not throw
        status.validateTransition(BatchStatus.NOT_COMPLETED);
    }

    @Test
    @DisplayName("Should allow valid transition from IN_PROGRESS to FAILED")
    void shouldAllowTransitionToFailed() {
        // Given
        BatchStatus status = BatchStatus.IN_PROGRESS;

        // When/Then - should not throw
        status.validateTransition(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("Should allow valid transition from IN_PROGRESS to CANCELLED")
    void shouldAllowTransitionToCancelled() {
        // Given
        BatchStatus status = BatchStatus.IN_PROGRESS;

        // When/Then - should not throw
        status.validateTransition(BatchStatus.CANCELLED);
    }

    @Test
    @DisplayName("Should reject transition from IN_PROGRESS to itself")
    void shouldRejectSameStateTransition() {
        // Given
        BatchStatus status = BatchStatus.IN_PROGRESS;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in status IN_PROGRESS");
    }

    @Test
    @DisplayName("Should reject transition from COMPLETED to any state")
    void shouldRejectTransitionFromCompleted() {
        // Given
        BatchStatus status = BatchStatus.COMPLETED;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal status COMPLETED");
    }

    @Test
    @DisplayName("Should reject transition from FAILED to any state")
    void shouldRejectTransitionFromFailed() {
        // Given
        BatchStatus status = BatchStatus.FAILED;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal status FAILED");
    }

    @Test
    @DisplayName("Should reject transition from CANCELLED to any state")
    void shouldRejectTransitionFromCancelled() {
        // Given
        BatchStatus status = BatchStatus.CANCELLED;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal status CANCELLED");
    }

    @Test
    @DisplayName("Should reject transition from NOT_COMPLETED to any state")
    void shouldRejectTransitionFromNotCompleted() {
        // Given
        BatchStatus status = BatchStatus.NOT_COMPLETED;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal status NOT_COMPLETED");
    }

    @Test
    @DisplayName("Should correctly identify terminal states")
    void shouldIdentifyTerminalStates() {
        // Terminal states
        assertThat(BatchStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(BatchStatus.FAILED.isTerminal()).isTrue();
        assertThat(BatchStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(BatchStatus.NOT_COMPLETED.isTerminal()).isTrue();

        // Non-terminal state
        assertThat(BatchStatus.IN_PROGRESS.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Should only allow file uploads in IN_PROGRESS state")
    void shouldOnlyAllowUploadsInProgress() {
        // Allow uploads
        assertThat(BatchStatus.IN_PROGRESS.allowsFileUpload()).isTrue();

        // Reject uploads in terminal states
        assertThat(BatchStatus.COMPLETED.allowsFileUpload()).isFalse();
        assertThat(BatchStatus.FAILED.allowsFileUpload()).isFalse();
        assertThat(BatchStatus.CANCELLED.allowsFileUpload()).isFalse();
        assertThat(BatchStatus.NOT_COMPLETED.allowsFileUpload()).isFalse();
    }

    @Test
    @DisplayName("Should reject COMPLETED to COMPLETED transition")
    void shouldRejectCompletedToCompletedTransition() {
        // Given
        BatchStatus status = BatchStatus.COMPLETED;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal status COMPLETED");
    }

    @Test
    @DisplayName("Should reject COMPLETED to FAILED transition")
    void shouldRejectCompletedToFailedTransition() {
        // Given
        BatchStatus status = BatchStatus.COMPLETED;

        // When/Then
        assertThatThrownBy(() -> status.validateTransition(BatchStatus.FAILED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal status COMPLETED");
    }
}
