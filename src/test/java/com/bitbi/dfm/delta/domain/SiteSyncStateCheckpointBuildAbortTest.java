package com.bitbi.dfm.delta.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict a scheduled checkpoint build leaves when it aborts before writing anything
 * (issue #224).
 *
 * <p>Since #213 a site with {@code last_checkpoint_seq = 0} and records applied reads as a
 * neutral "waiting for the first nightly build". A whole-site abort
 * ({@code frame_too_large}, a fold over the heap budget, a deferral, …) writes no
 * {@code checkpoints} row and leaves the pointer at zero, so thirty failed nights carry
 * byte-for-byte the payload of a site ingested this afternoon. The abort is what makes those
 * distinguishable, and it is written only on the abort path — a healthy build advances the
 * pointer and does not touch these columns.</p>
 */
@DisplayName("SiteSyncState — last scheduled-build abort")
class SiteSyncStateCheckpointBuildAbortTest {

    @Test
    @DisplayName("a site that was never visited by a failing build carries no abort")
    void shouldStartWithoutAnAbort() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());

        assertThat(state.getLastCheckpointBuildAbort()).isNull();
        assertThat(state.getLastCheckpointBuildAbortAt()).isNull();
        assertThat(state.getLastCheckpointBuildMessage()).isNull();
    }

    @Test
    @DisplayName("recording the abort stamps the reason, time and message in one write")
    void shouldRecordTheAbortInOneWrite() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.advanceWatermark(1_155L);

        state.recordCheckpointBuildAbort(CheckpointBuildAbort.FOLD_TOO_LARGE, "fold outgrew the budget");

        assertThat(state.getLastCheckpointBuildAbort()).isEqualTo(CheckpointBuildAbort.FOLD_TOO_LARGE);
        assertThat(state.getLastCheckpointBuildAbortAt()).isNotNull();
        assertThat(state.getLastCheckpointBuildMessage()).isEqualTo("fold outgrew the budget");
        assertThat(state.getLastCheckpointSeq()).isZero();
        assertThat(state.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("recording an abort does not look like sync activity")
    void shouldNotMoveUpdatedAt() {
        // Stalled is a statement about the client, not about the nightly tick. Stamping updatedAt
        // here would hide a silent extractor behind a write the client never made.
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.advanceWatermark(40L);
        java.time.LocalDateTime before = state.getUpdatedAt();

        state.recordCheckpointBuildAbort(CheckpointBuildAbort.DEFERRED, "fold budget held");

        assertThat(state.getUpdatedAt()).isSameAs(before);
    }

    @Test
    @DisplayName("a later abort replaces the previous verdict")
    void shouldReplaceThePreviousAbort() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.recordCheckpointBuildAbort(CheckpointBuildAbort.DEFERRED, "budget held");

        state.recordCheckpointBuildAbort(CheckpointBuildAbort.FRAME_TOO_LARGE, "frame crossed the ceiling");

        assertThat(state.getLastCheckpointBuildAbort()).isEqualTo(CheckpointBuildAbort.FRAME_TOO_LARGE);
        assertThat(state.getLastCheckpointBuildMessage()).isEqualTo("frame crossed the ceiling");
    }

    @Test
    @DisplayName("an over-long message is truncated rather than losing the whole verdict")
    void shouldTruncateAnOverLongMessage() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());

        state.recordCheckpointBuildAbort(CheckpointBuildAbort.FAILED, "x".repeat(5_000));

        assertThat(state.getLastCheckpointBuildMessage()).hasSize(SiteSyncState.MAX_REBUILD_MESSAGE_LENGTH);
        assertThat(state.getLastCheckpointBuildMessage()).endsWith("…");
    }

    @Test
    @DisplayName("a NUL and other control characters never reach the column")
    void shouldStripControlCharacters() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());

        state.recordCheckpointBuildAbort(CheckpointBuildAbort.FAILED, "bad\u0000value\nsecond line");

        assertThat(state.getLastCheckpointBuildMessage()).isEqualTo("bad value second line");
    }

    @Test
    @DisplayName("a wipe drops the abort together with the checkpoints it described")
    void shouldDropTheAbortOnWipe() {
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.advanceWatermark(500L);
        state.recordCheckpointBuildAbort(CheckpointBuildAbort.FOLD_TOO_LARGE, "too big");

        state.resetForWipe();

        assertThat(state.getLastCheckpointBuildAbort()).isNull();
        assertThat(state.getLastCheckpointBuildAbortAt()).isNull();
        assertThat(state.getLastCheckpointBuildMessage()).isNull();
        assertThat(state.getLastCheckpointSeq()).isZero();
    }

    @Test
    @DisplayName("a re-baseline drops the abort too")
    void shouldDropTheAbortOnRebaseline() {
        // A re-baseline deletes every checkpoint of the site (#142) and zeroes the pointer, so an
        // abort recorded against the discarded baseline would make a freshly re-baselined site
        // read as "the first build already failed" before the new baseline has been attempted.
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.advanceWatermark(500L);
        state.recordCheckpoint(400L);
        state.recordCheckpointBuildAbort(CheckpointBuildAbort.FAILED, "boom");

        state.resetForRebaseline(500L);

        assertThat(state.getLastCheckpointBuildAbort()).isNull();
        assertThat(state.getLastCheckpointBuildAbortAt()).isNull();
        assertThat(state.getLastCheckpointBuildMessage()).isNull();
        assertThat(state.getLastCheckpointSeq()).isZero();
    }

    @Test
    @DisplayName("advancing the pointer does not clear the abort")
    void shouldLeaveTheAbortStandingWhenACheckpointIsRecorded() {
        // DoD: no write on a healthy build. The pointer moving is what takes the site out of
        // awaiting-first-checkpoint; the abort columns stay as history and are not read once
        // lastCheckpointSeq > 0.
        SiteSyncState state = SiteSyncState.initial(UUID.randomUUID());
        state.recordCheckpointBuildAbort(CheckpointBuildAbort.DEFERRED, "budget held");

        state.recordCheckpoint(1_155L);

        assertThat(state.getLastCheckpointSeq()).isEqualTo(1_155L);
        assertThat(state.getLastCheckpointBuildAbort()).isEqualTo(CheckpointBuildAbort.DEFERRED);
        assertThat(state.getLastCheckpointBuildMessage()).isEqualTo("budget held");
    }
}
