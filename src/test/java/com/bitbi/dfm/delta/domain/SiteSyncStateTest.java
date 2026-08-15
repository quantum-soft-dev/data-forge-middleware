package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Epoch semantics of the site sync state (035 — site history wipe, issue #89).
 *
 * <p>The generation counter is the whole client contract: it is what tells the Delta v2 client to
 * drop its local journal and reset its seq counter to zero. These tests pin down exactly which
 * operations move it and which must not.</p>
 */
@DisplayName("SiteSyncState — wipe epoch")
class SiteSyncStateTest {

    private static SiteSyncState synced() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.advanceWatermark(500L);
        state.recordCheckpoint(400L);
        state.recordSchemaVersion(7);
        return state;
    }

    @Test
    @DisplayName("initial state starts at generation 0 with no wipe pending")
    void shouldStartAtGenerationZero() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());

        assertThat(state.getGeneration()).isZero();
        assertThat(state.isWipePending()).isFalse();
    }

    @Test
    @DisplayName("resetForWipe zeroes the watermark, checkpoint pointer and schema version")
    void shouldZeroEverythingOnWipe() {
        SiteSyncState state = synced();

        state.resetForWipe();

        assertThat(state.getLastAppliedSeq()).isZero();
        assertThat(state.getLastCheckpointSeq()).isZero();
        assertThat(state.getLastCheckpointAt()).isNull();
        assertThat(state.getSchemaVersion()).isZero();
    }

    @Test
    @DisplayName("resetForWipe raises the rebaseline request so GetSyncState answers NEED_REBASELINE")
    void shouldRaiseRebaselineRequestOnWipe() {
        SiteSyncState state = synced();
        state.requestRebaseline();
        state.markRebaselineNotified();
        state.requestRebuild();

        state.resetForWipe();

        assertThat(state.isRebaselineRequested()).isTrue();
        // Re-armed from scratch: the client has not been told about *this* request yet, so a
        // cancellation before its next poll still provably reaches it.
        assertThat(state.getRebaselineNotifiedAt()).isNull();
        assertThat(state.isRebuildRequested()).isFalse();
    }

    @Test
    @DisplayName("resetForWipe increments the generation and flags the wipe as pending")
    void shouldIncrementGenerationOnWipe() {
        SiteSyncState state = synced();

        state.resetForWipe();

        assertThat(state.getGeneration()).isEqualTo(1L);
        assertThat(state.isWipePending()).isTrue();
    }

    @Test
    @DisplayName("generation is monotonic across repeated wipes")
    void shouldKeepGenerationMonotonic() {
        SiteSyncState state = synced();

        state.resetForWipe();
        state.resetForWipe();
        state.resetForWipe();

        assertThat(state.getGeneration()).isEqualTo(3L);
    }

    @Test
    @DisplayName("an ordinary re-baseline never bumps the generation")
    void shouldNotBumpGenerationOnRebaseline() {
        SiteSyncState state = synced();
        state.resetForWipe();

        state.resetForRebaseline(0L);

        assertThat(state.getGeneration()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an ordinary re-baseline bumps the baseline epoch the checkpoint guard keys on")
    void shouldBumpBaselineEpochOnRebaseline() {
        // The re-baseline deletes every checkpoint and zeroes the pointer exactly as a wipe does, so
        // a build that overlaps it must be refused too (issue #142). The generation cannot carry
        // that: it is the wire signal (035) and telling the client to reset its counters on an
        // ordinary re-baseline would be a protocol change.
        SiteSyncState state = synced();

        state.resetForRebaseline(499L);

        assertThat(state.getBaselineEpoch()).isEqualTo(1L);
        assertThat(state.getGeneration()).isZero();
    }

    @Test
    @DisplayName("a wipe moves both epochs, so the guard's epoch is never the weaker signal")
    void shouldBumpBothEpochsOnWipe() {
        SiteSyncState state = synced();

        state.resetForWipe();

        assertThat(state.getGeneration()).isEqualTo(1L);
        assertThat(state.getBaselineEpoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the baseline epoch is monotonic across mixed wipes and re-baselines")
    void shouldKeepBaselineEpochMonotonic() {
        SiteSyncState state = synced();

        state.resetForRebaseline(10L);
        state.resetForWipe();
        state.resetForRebaseline(0L);

        assertThat(state.getBaselineEpoch()).isEqualTo(3L);
    }

    @Test
    @DisplayName("an ordinary re-baseline keeps the schema version, unlike a wipe")
    void shouldKeepSchemaVersionOnRebaseline() {
        SiteSyncState state = synced();

        state.resetForRebaseline(499L);

        assertThat(state.getSchemaVersion()).isEqualTo(7);
        assertThat(state.isWipePending()).isFalse();
    }

    @Test
    @DisplayName("consumeWipePending fires once per wipe")
    void shouldConsumeWipePendingExactlyOnce() {
        SiteSyncState state = synced();
        state.resetForWipe();

        assertThat(state.consumeWipePending()).isTrue();
        assertThat(state.consumeWipePending()).isFalse();
        assertThat(state.isWipePending()).isFalse();
    }

    @Test
    @DisplayName("consumeWipePending is false when no wipe is pending")
    void shouldNotConsumeWhenNoWipePending() {
        SiteSyncState state = synced();

        assertThat(state.consumeWipePending()).isFalse();
    }
}
