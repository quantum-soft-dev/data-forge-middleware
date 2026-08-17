package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B7 (023) — forced out-of-schedule checkpoint rebuild: sets the persistent rebuild flag,
 * runs {@code CheckpointService.buildCheckpoint} asynchronously, and always clears the flag
 * when the attempt finishes (success or failure).
 *
 * <p>Review round 3: the flag is a durable DB row while the queued task is in-memory only, so
 * the service must also (a) short-circuit duplicate requests, (b) clear the flag when the
 * executor rejects the task, and (c) re-drive flagged sites on startup — otherwise the UI's
 * "Rebuild queued" chip sticks forever after a restart or a full queue.</p>
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

    @Test
    void requestRebuildSetsFlagBuildsCheckpointAndClearsFlag() {
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenReturn(Map.of());

        assertTrue(service.requestRebuild(SITE));

        InOrder inOrder = inOrder(syncStateService, checkpointService);
        inOrder.verify(syncStateService).requestRebuild(SITE);
        inOrder.verify(checkpointService).rebuildFromFrame(SITE);
        inOrder.verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void clearsFlagEvenWhenBuildFails() {
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenThrow(new RuntimeException("s3 down"));

        assertDoesNotThrow(() -> service.requestRebuild(SITE));

        verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void keepsTheFlagWhenTheBuildEndedBecauseTheProcessIsShuttingDown() {
        // Review of PR #169, round 2. Since issue #162 CheckpointService swallows a shutdown and
        // returns an empty fold, which here would read as success: the log would say "completed"
        // and the finally would spend the very flag resumePendingRebuilds exists to re-drive, so an
        // operator's click during a rollout would vanish. The scheduled tick can rely on the
        // nightly work list finding the site again; this path has only the flag.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE)).thenAnswer(invocation -> {
            shuttingDown = true;
            return Map.of();
        });

        assertTrue(service.requestRebuild(SITE));

        verify(syncStateService, never()).clearRebuildRequested(SITE);
    }

    @Test
    void clearsTheFlagWhenS3WouldNotSayWhetherTheFrameIsThere() {
        // Issue #157, rounds 1 and 2 of review together. The rebuild did not run, so it must not be
        // logged as completed (round 1) — but the flag must still be released (round 2). Keeping it
        // would strand the request: only a restart re-drives it, and requestRebuild short-circuits
        // while it is set, so the operator could neither wait for it nor ask again once the
        // permission came back. The shutdown sibling can keep the flag because a restart is
        // imminent by definition; a bucket-policy incident carries no such promise. So this is
        // settled like any other failed attempt — released, and loudly enough to re-request.
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.rebuildFromFrame(SITE))
                .thenThrow(new CheckpointService.FramePresenceUnknownException(SITE, 7L));

        assertTrue(service.requestRebuild(SITE));

        verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void clearsTheFlagWhenTheRebuildWasDeferredBehindTheFoldBudget() {
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

        verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void keepsTheFlagWhenTheDeferralWasTheProcessShuttingDown() {
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

        verify(syncStateService, never()).clearRebuildRequested(SITE);
    }

    @Test
    void duplicateRequestShortCircuitsWithoutASecondBuild() {
        // Flag already set: a second click must not queue a second full rebuild whose
        // sibling's finally-clear would flip the UI to "idle" mid-run.
        when(syncStateService.requestRebuild(SITE)).thenReturn(false);

        assertFalse(service.requestRebuild(SITE));

        verify(checkpointService, never()).rebuildFromFrame(any());
        verify(syncStateService, never()).clearRebuildRequested(any());
    }

    @Test
    void queueRejectionClearsFlagAndPropagates() {
        // The flag transaction commits before the submit; a full queue must not leave the
        // durable flag orphaned (UI stuck on "Rebuild queued" with no task behind it).
        Executor rejecting = task -> { throw new RejectedExecutionException("queue full"); };
        DeltaCheckpointRebuildService rejectingService = new DeltaCheckpointRebuildService(
                syncStateService, checkpointService, shutdownSignal, rejecting);
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);

        assertThrows(RejectedExecutionException.class, () -> rejectingService.requestRebuild(SITE));

        verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void resumePendingRebuildsRedrivesFlaggedSites() {
        // Startup recovery: a restart between flag-commit and task execution orphans the flag;
        // flagged sites are re-driven (and their flags cleared by the run's finally).
        UUID other = UUID.randomUUID();
        when(syncStateService.findSitesWithPendingRebuild()).thenReturn(List.of(SITE, other));
        when(checkpointService.rebuildFromFrame(any())).thenReturn(Map.of());

        service.resumePendingRebuilds();

        verify(checkpointService).rebuildFromFrame(SITE);
        verify(checkpointService).rebuildFromFrame(other);
        verify(syncStateService).clearRebuildRequested(SITE);
        verify(syncStateService).clearRebuildRequested(other);
    }
}
