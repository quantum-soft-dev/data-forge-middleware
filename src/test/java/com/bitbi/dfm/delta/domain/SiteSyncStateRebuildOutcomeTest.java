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
 * <em>with</em> the flag release (never one without the other), and it lives exactly as long as the
 * checkpoints it describes — everything that discards them, a wipe and an ordinary re-baseline
 * alike, discards it too.</p>
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
    @DisplayName("the cut never splits a surrogate pair")
    void shouldNotSplitASurrogatePair() {
        // Raised in review: bounding the length is not the same as making the value storable. An
        // over-long message ending on the wrong char would leave an unpaired surrogate, which the
        // driver's UTF-8 encoder rejects — a throw from the finally that both loses the verdict and
        // strands rebuild_requested, which is the failure this bounding exists to prevent.
        SiteSyncState state = requested();
        String withEmoji = "x".repeat(SiteSyncState.MAX_REBUILD_MESSAGE_LENGTH - 2) + "\uD83D\uDCA5"
                + "y".repeat(50);

        state.recordRebuildOutcome(CheckpointRebuildOutcome.FAILED, withEmoji);

        String stored = state.getLastRebuildMessage();
        assertThat(stored.length()).isLessThanOrEqualTo(SiteSyncState.MAX_REBUILD_MESSAGE_LENGTH);
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 2))).isFalse();
        assertThat(stored.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    @DisplayName("a NUL and other control characters never reach the column")
    void shouldStripControlCharacters() {
        // PostgreSQL rejects U+0000 in a text value outright, and a JDBC error can quote a row that
        // contains one. Replaced rather than dropped, since a multi-line message is still readable.
        SiteSyncState state = requested();

        state.recordRebuildOutcome(CheckpointRebuildOutcome.FAILED, "bad\u0000value\nsecond line");

        assertThat(state.getLastRebuildMessage()).isEqualTo("bad value second line");
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
    @DisplayName("a re-baseline drops the verdict too, and keeps the request flag")
    void shouldDropTheVerdictOnRebaseline() {
        // Raised in review. A re-baseline deletes every checkpoint row of the site (#142), so the
        // verdict describes nothing afterwards — the same reason the wipe drops it. The request
        // flag is a different question ("a rebuild is owed") and this reset deliberately does not
        // answer it, so the two are not cleared together.
        SiteSyncState state = requested();
        state.recordRebuildOutcome(CheckpointRebuildOutcome.FAILED, "boom");
        state.requestRebuild();

        state.resetForRebaseline(500L);

        assertThat(state.getLastRebuildOutcome()).isNull();
        assertThat(state.getLastRebuildOutcomeAt()).isNull();
        assertThat(state.getLastRebuildMessage()).isNull();
        assertThat(state.isRebuildRequested()).isTrue();
    }
}
