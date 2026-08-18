package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict a forced checkpoint rebuild leaves behind (issue #186).
 *
 * <p>Before this, {@code rebuild_requested} was the whole record of an operator's click: it went up
 * on the request and down when the attempt settled, whatever the attempt had done. Three of the
 * four endings ran nothing at all, and all four looked identical from outside the pod. The verdict
 * is what makes them distinguishable, so these tests pin the two properties it needs: it is written
 * <em>with</em> the flag release (never one without the other), and it travels with the flag —
 * anything that resets the flag resets the verdict, anything that leaves the flag alone leaves the
 * verdict alone.</p>
 */
@DisplayName("SiteSyncState — last forced-rebuild outcome")
class SiteSyncStateRebuildOutcomeTest {

    private static SiteSyncState requested() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.advanceWatermark(500L);
        state.recordCheckpoint(400L);
        state.requestRebuild();
        return state;
    }

    @Test
    @DisplayName("a site that was never rebuilt carries no verdict")
    void shouldStartWithoutAnOutcome() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());

        assertThat(state.getLastRebuildOutcome()).isNull();
        assertThat(state.getLastRebuildOutcomeAt()).isNull();
        assertThat(state.getLastRebuildMessage()).isNull();
    }

    @Test
    @DisplayName("recording the outcome releases the request flag in the same write")
    void shouldReleaseTheFlagWithTheVerdict() {
        SiteSyncState state = requested();

        state.recordRebuildOutcome(CheckpointRebuildOutcome.COMPLETED, null);

        assertThat(state.isRebuildRequested()).isFalse();
        assertThat(state.getLastRebuildOutcome()).isEqualTo(CheckpointRebuildOutcome.COMPLETED);
        assertThat(state.getLastRebuildOutcomeAt()).isNotNull();
        assertThat(state.getLastRebuildMessage()).isNull();
    }

    @Test
    @DisplayName("a later attempt replaces the previous verdict")
    void shouldReplaceThePreviousVerdict() {
        SiteSyncState state = requested();
        state.recordRebuildOutcome(CheckpointRebuildOutcome.FAILED, "boom");

        state.requestRebuild();
        state.recordRebuildOutcome(CheckpointRebuildOutcome.COMPLETED, null);

        assertThat(state.getLastRebuildOutcome()).isEqualTo(CheckpointRebuildOutcome.COMPLETED);
        assertThat(state.getLastRebuildMessage()).isNull();
    }

    @Test
    @DisplayName("an over-long message is truncated rather than losing the whole verdict")
    void shouldTruncateAnOverLongMessage() {
        // The message is whatever the failure said, and a JDBC failure can say a great deal. A
        // value wider than the column throws at flush, which would lose the verdict entirely —
        // exactly the invisibility this ticket exists to remove — so the entity bounds it itself.
        SiteSyncState state = requested();

        state.recordRebuildOutcome(CheckpointRebuildOutcome.FAILED, "x".repeat(5_000));

        assertThat(state.getLastRebuildMessage()).hasSize(SiteSyncState.MAX_REBUILD_MESSAGE_LENGTH);
        assertThat(state.getLastRebuildMessage()).endsWith("…");
    }

    @Test
    @DisplayName("a message that fits is stored verbatim")
    void shouldKeepAMessageThatFits() {
        SiteSyncState state = requested();

        state.recordRebuildOutcome(CheckpointRebuildOutcome.DEFERRED, "the nightly build held the budget");

        assertThat(state.getLastRebuildMessage()).isEqualTo("the nightly build held the budget");
    }

    @Test
    @DisplayName("a wipe drops the verdict together with the request flag")
    void shouldDropTheVerdictOnWipe() {
        // resetForWipe already puts the row back to what a brand-new site has, request flag
        // included. A verdict about checkpoints the wipe has just deleted describes nothing.
        SiteSyncState state = requested();
        state.recordRebuildOutcome(CheckpointRebuildOutcome.COMPLETED, null);

        state.resetForWipe();

        assertThat(state.getLastRebuildOutcome()).isNull();
        assertThat(state.getLastRebuildOutcomeAt()).isNull();
        assertThat(state.getLastRebuildMessage()).isNull();
    }

    @Test
    @DisplayName("a re-baseline keeps the verdict, as it keeps the request flag")
    void shouldKeepTheVerdictOnRebaseline() {
        // The verdict travels with the flag, and resetForRebaseline deliberately leaves the flag
        // alone: a rebuild queued while the client re-uploads is still a rebuild the operator asked
        // for. Clearing one and not the other would make the pair mean two different things.
        SiteSyncState state = requested();
        state.recordRebuildOutcome(CheckpointRebuildOutcome.FAILED, "boom");

        state.resetForRebaseline(500L);

        assertThat(state.getLastRebuildOutcome()).isEqualTo(CheckpointRebuildOutcome.FAILED);
        assertThat(state.getLastRebuildMessage()).isEqualTo("boom");
    }
}
