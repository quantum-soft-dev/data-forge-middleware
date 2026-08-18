package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.CheckpointRebuildOutcome;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * B7 (023) — forced out-of-schedule checkpoint rebuild: sets the persistent rebuild flag,
 * runs {@code CheckpointService.rebuildFromFrame} asynchronously, and settles the flag
 * when the attempt finishes.
 *
 * <p>Review round 3: the flag is a durable DB row while the queued task is in-memory only, so
 * the service must also (a) short-circuit duplicate requests, (b) clear the flag when the
 * executor rejects the task, and (c) re-drive flagged sites on startup — otherwise the UI's
 * "Rebuild queued" chip sticks forever after a restart or a full queue.</p>
 *
 * <p>Issue #186: releasing the flag is not the same as reporting the attempt. Every ending that
 * releases it now records <em>which</em> ending it was, because from outside the pod the four were
 * indistinguishable and three of them ran nothing. The shutdown endings keep the flag and
 * deliberately record nothing: the request has not finished, it is about to be re-driven.</p>
 */
class DeltaCheckpointRebuildServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);

    private volatile boolean shuttingDown;
    private final ApplicationShutdownSignal shutdownSignal = new ApplicationShutdownSignal() {
        @Override
        public boolean isShuttingDown() {
            return shuttingDown;
        }
    };

    /** Direct executor: the async hop runs inline so the full lifecycle is observable. */
    private final DeltaCheckpointRebuildService service = new DeltaCheckpointRebuildService(
            syncStateService, checkpointService, shutdownSignal, Runnable::run);

    private String recordedMessage(CheckpointRebuildOutcome outcome) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(syncStateService).recordRebuildOutcome(eq(SITE), eq(outcome), message.capture());
        return message.getValue();
    }

    @Test
    void requestRebuildSetsFlagBuildsCheckpointAndRecordsCompletion() {
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenReturn(Map.of());

        assertTrue(service.requestRebuild(SITE));

        InOrder inOrder = inOrder(syncStateService, checkpointService);
        inOrder.verify(syncStateService).requestRebuild(SITE);
        inOrder.verify(checkpointService).rebuildFromFrame(SITE);
        // A completed rebuild has nothing to explain, so it carries no message: the outcome and
        // its timestamp are the whole answer, and a stock sentence would only be noise in the UI.
        inOrder.verify(syncStateService)
                .recordRebuildOutcome(eq(SITE), eq(CheckpointRebuildOutcome.COMPLETED), isNull());
    }

    @Test
    void recordsAFailureWhenTheBuildThrows() {
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenThrow(new IllegalStateException("s3 down"));

        assertDoesNotThrow(() -> service.requestRebuild(SITE));

        // The exception's own type and text: the operator gets the same thing the pod log has,
        // which is the point of the ticket — the log was the only place it existed.
        assertThat(recordedMessage(CheckpointRebuildOutcome.FAILED))
                .contains("IllegalStateException")
                .contains("s3 down");
    }

    @Test
    void keepsTheFlagAndRecordsNothingWhenTheBuildEndedBecauseTheProcessIsShuttingDown() {
        // Review of PR #169, round 2. Since issue #162 CheckpointService swallows a shutdown and
        // returns an empty fold, which here would read as success: the log would say "completed"
        // and the finally would spend the very flag resumePendingRebuilds exists to re-drive, so an
        // operator's click during a rollout would vanish. The scheduled tick can rely on the
        // nightly work list finding the site again; this path has only the flag. No verdict either
        // (#186): the request has not finished, and writing one would contradict the flag that is
        // deliberately still up.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenAnswer(invocation -> {
            shuttingDown = true;
            return Map.of();
        });

        assertTrue(service.requestRebuild(SITE));

        verify(syncStateService, never()).recordRebuildOutcome(any(), any(), any());
    }

    @Test
    void recordsThatS3WouldNotSayWhetherTheFrameIsThere() {
        // Issue #157, rounds 1 and 2 of review together. The rebuild did not run, so it must not be
        // logged as completed (round 1) — but the flag must still be released (round 2). Keeping it
        // would strand the request: only a restart re-drives it, and requestRebuild short-circuits
        // while it is set, so the operator could neither wait for it nor ask again once the
        // permission came back. The shutdown sibling can keep the flag because a restart is
        // imminent by definition; a bucket-policy incident carries no such promise. So this is
        // settled like any other failed attempt — released, and (since #186) with a verdict of its
        // own rather than sharing FAILED, because the remedy is a bucket policy and not a retry.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE))
                .thenThrow(new CheckpointService.FramePresenceUnknownException(SITE, 7L));

        assertTrue(service.requestRebuild(SITE));

        // The verdict keeps the exception's diagnosis and replaces its advice: the exception is
        // worded for CheckpointScheduler, which does revisit the site, whereas nothing re-drives a
        // forced rebuild but another click.
        assertThat(recordedMessage(CheckpointRebuildOutcome.FRAME_UNAVAILABLE))
                .contains("read denied")
                .contains("Request the rebuild again")
                .doesNotContain("the next tick tries again");
    }

    @Test
    void recordsADeferralWhenTheRebuildLostTheFoldBudget() {
        // Issue #178. The nightly sweep held the process's fold budget for longer than
        // delta.checkpoint.fold-wait-seconds, so this rebuild never folded anything. Settled like
        // the read denial above and not like the shutdown: nothing re-drives a held flag here —
        // the tick calls buildCheckpoint, never rebuildFromFrame — and requestRebuild
        // short-circuits while it is set, so keeping it would leave the operator unable to ask
        // again once the sweep finished.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE))
                .thenThrow(new CheckpointFoldBudget.BuildDeferredException(SITE, 600_000L, false, true));

        assertTrue(service.requestRebuild(SITE));

        assertThat(recordedMessage(CheckpointRebuildOutcome.DEFERRED))
                .contains("held the process's fold budget for the whole wait")
                .contains("delta.checkpoint.fold-wait-seconds")
                .doesNotContain("the next tick tries again");
    }

    @Test
    void keepsTheFlagAndRecordsNothingWhenTheDeferralWasTheProcessShuttingDown() {
        // Raised in review of #178. The wait for the fold budget is shutdown-aware, so a rollout
        // during it ends as a deferral too — and that one is #162's case, not #157's: settling it
        // like an ordinary deferral would clear the durable flag and lose the request that
        // resumePendingRebuilds() exists to re-drive in the next process.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenAnswer(invocation -> {
            shuttingDown = true;
            throw new CheckpointFoldBudget.BuildDeferredException(SITE, 600_000L, true, true);
        });

        assertTrue(service.requestRebuild(SITE));

        verify(syncStateService, never()).recordRebuildOutcome(any(), any(), any());
    }

    @Test
    void doesNotBlameContentionForADeferralThatNeverSpentItsWait() {
        // Raised in review. `endedEarly` also covers a bare interrupt, and a probe that did not
        // wait at all sets mayWait=false — neither is contention, so neither may prescribe raising
        // delta.checkpoint.fold-wait-seconds. It is the same split waitWasSpent() makes for
        // delta.checkpoint.builds.deferred, and the log line beside this has always made it.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE))
                .thenThrow(new CheckpointFoldBudget.BuildDeferredException(SITE, 0L, false, false));

        assertTrue(service.requestRebuild(SITE));

        assertThat(recordedMessage(CheckpointRebuildOutcome.DEFERRED))
                .contains("did not get the process's fold budget")
                .doesNotContain("fold-wait-seconds");
    }

    @Test
    void doesNotReportADiscardedBuildAsCompleted() {
        // Raised in review, round 2, and it is this ticket's own property violated: a build whose
        // site was wiped or re-baselined under it (#136/#142) publishes nothing, and until
        // CheckpointService threw for it, runRebuild saw a normal return and wrote COMPLETED — a
        // green "Rebuilt" chip over a rebuild that did nothing. Worse, that verdict lands *after*
        // the reset's own clearRebuildOutcome(), so it sticks.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE))
                .thenThrow(new CheckpointService.BuildDiscardedException(SITE, "history replaced"));

        assertTrue(service.requestRebuild(SITE));

        assertThat(recordedMessage(CheckpointRebuildOutcome.DISCARDED))
                .contains("history was replaced")
                .contains("Request it again");
    }

    @Test
    void doesNotClaimToHaveRebuiltASiteWithNoHistory() {
        // The other false COMPLETED: no seed frame and no segments means there was no source to
        // rebuild from. The nightly tick passes over such a site quietly; a forced rebuild is a
        // question, and "rebuilt" is not a truthful answer to it.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE))
                .thenThrow(new CheckpointService.NothingToRebuildException(SITE));

        assertTrue(service.requestRebuild(SITE));

        assertThat(recordedMessage(CheckpointRebuildOutcome.NOTHING_TO_REBUILD))
                .contains("nothing to rebuild");
    }

    @Test
    void duplicateRequestShortCircuitsWithoutASecondBuild() {
        // Flag already set: a second click must not queue a second full rebuild whose
        // sibling's settle would flip the UI to "idle" mid-run.
        when(syncStateService.requestRebuild(SITE)).thenReturn(false);

        assertFalse(service.requestRebuild(SITE));

        verify(checkpointService, never()).rebuildFromFrame(any());
        verify(syncStateService, never()).recordRebuildOutcome(any(), any(), any());
    }

    @Test
    void queueRejectionRecordsTheRefusalAndPropagates() {
        // The flag transaction commits before the submit; a full queue must not leave the
        // durable flag orphaned (UI stuck on "Rebuild queued" with no task behind it). The caller
        // also sees the exception, but the verdict is what a second operator reading the site a
        // minute later has — and it is the same surface the startup-recovery rejection writes to.
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        DeltaCheckpointRebuildService rejectingService = new DeltaCheckpointRebuildService(
                syncStateService, checkpointService, shutdownSignal, rejecting);
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);

        assertThrows(RejectedExecutionException.class, () -> rejectingService.requestRebuild(SITE));

        // The refusal's own words, not an asserted cause: ThreadPoolTaskExecutor raises this both
        // for a full queue and for "executor shutting down", and naming the wrong one sends the
        // operator after a capacity problem during a routine rollout (#171's lesson).
        assertThat(recordedMessage(CheckpointRebuildOutcome.FAILED))
                .contains("never started")
                .contains("queue full");
    }

    @Test
    void resumePendingRebuildsRedrivesFlaggedSites() {
        // Startup recovery: a restart between flag-commit and task execution orphans the flag;
        // flagged sites are re-driven (and their flags settled by the run).
        UUID other = UUID.randomUUID();
        when(syncStateService.findSitesWithPendingRebuild()).thenReturn(List.of(SITE, other));
        when(checkpointService.rebuildFromFrame(any())).thenReturn(Map.of());

        service.resumePendingRebuilds();

        verify(checkpointService).rebuildFromFrame(SITE);
        verify(checkpointService).rebuildFromFrame(other);
        verify(syncStateService)
                .recordRebuildOutcome(eq(SITE), eq(CheckpointRebuildOutcome.COMPLETED), isNull());
        verify(syncStateService)
                .recordRebuildOutcome(eq(other), eq(CheckpointRebuildOutcome.COMPLETED), isNull());
    }

    @Test
    void startupRecoveryRecordsTheRefusalWhenTheQueueIsFull() {
        // The one rejection nobody is watching: no HTTP caller to answer, so before #186 a restart
        // with a full queue dropped the flag and left nothing at all behind.
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        DeltaCheckpointRebuildService rejectingService = new DeltaCheckpointRebuildService(
                syncStateService, checkpointService, shutdownSignal, rejecting);
        when(syncStateService.findSitesWithPendingRebuild()).thenReturn(List.of(SITE));

        assertDoesNotThrow(rejectingService::resumePendingRebuilds);

        assertThat(recordedMessage(CheckpointRebuildOutcome.FAILED))
                .contains("never started")
                .contains("queue full");
    }
}
