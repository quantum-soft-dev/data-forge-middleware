package com.bitbi.dfm.delta.application;

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

    /** Direct executor: the async hop runs inline so the full lifecycle is observable. */
    private final DeltaCheckpointRebuildService service =
            new DeltaCheckpointRebuildService(syncStateService, checkpointService, Runnable::run);

    @Test
    void requestRebuildSetsFlagBuildsCheckpointAndClearsFlag() {
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.buildCheckpoint(SITE, true)).thenReturn(Map.of());

        assertTrue(service.requestRebuild(SITE));

        InOrder inOrder = inOrder(syncStateService, checkpointService);
        inOrder.verify(syncStateService).requestRebuild(SITE);
        inOrder.verify(checkpointService).buildCheckpoint(SITE, true);
        inOrder.verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void clearsFlagEvenWhenBuildFails() {
        when(syncStateService.requestRebuild(SITE)).thenReturn(true);
        when(checkpointService.buildCheckpoint(SITE, true)).thenThrow(new RuntimeException("s3 down"));

        assertDoesNotThrow(() -> service.requestRebuild(SITE));

        verify(syncStateService).clearRebuildRequested(SITE);
    }

    @Test
    void duplicateRequestShortCircuitsWithoutASecondBuild() {
        // Flag already set: a second click must not queue a second full rebuild whose
        // sibling's finally-clear would flip the UI to "idle" mid-run.
        when(syncStateService.requestRebuild(SITE)).thenReturn(false);

        assertFalse(service.requestRebuild(SITE));

        verify(checkpointService, never()).buildCheckpoint(any(), anyBoolean());
        verify(syncStateService, never()).clearRebuildRequested(any());
    }

    @Test
    void queueRejectionClearsFlagAndPropagates() {
        // The flag transaction commits before the submit; a full queue must not leave the
        // durable flag orphaned (UI stuck on "Rebuild queued" with no task behind it).
        Executor rejecting = task -> { throw new RejectedExecutionException("queue full"); };
        DeltaCheckpointRebuildService rejectingService =
                new DeltaCheckpointRebuildService(syncStateService, checkpointService, rejecting);
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
        when(checkpointService.buildCheckpoint(any(), anyBoolean())).thenReturn(Map.of());

        service.resumePendingRebuilds();

        verify(checkpointService).buildCheckpoint(SITE, true);
        verify(checkpointService).buildCheckpoint(other, true);
        verify(syncStateService).clearRebuildRequested(SITE);
        verify(syncStateService).clearRebuildRequested(other);
    }
}
