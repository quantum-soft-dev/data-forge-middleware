package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.DeltaRebaselineCancellationService.Outcome;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.RebaselineCancellation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #84 — cancelling a requested re-baseline must say whether the full snapshot was actually
 * averted, without breaking the rule that makes a dropped snapshot recoverable: the request is held
 * until the snapshot commits so a snapshot that dies part-way is retried rather than silently
 * downgraded to an ordinary delta on a baseline that was never replaced (033/#87).
 */
class DeltaRebaselineCancellationServiceTest {

    private static final UUID SITE = UUID.randomUUID();
    private static final int TIMEOUT_MINUTES = 60;

    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final DeltaRebaselineCancellationService service =
            new DeltaRebaselineCancellationService(syncStateService, batchRepository, TIMEOUT_MINUTES);

    /** An open batch of the given mode; {@code live=false} makes it one the sweeper will reap. */
    private void openSession(String sessionMode, boolean live) {
        Batch batch = mock(Batch.class);
        when(batch.getSessionMode()).thenReturn(sessionMode);
        when(batch.isExpired(TIMEOUT_MINUTES)).thenReturn(!live);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.of(batch));
    }

    private void noSession() {
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.empty());
    }

    @Test
    void reportsCancelledWhenTheClientWasNeverToldAndNothingIsRunning() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        noSession();

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
        assertEquals("cancelled", Outcome.CANCELLED.status());
    }

    @Test
    void reportsCancelledWhileAnOrdinaryDeltaSessionIsOpen() {
        // A CONTINUOUS session holds its batch IN_PROGRESS for hours (029: batch = session), and the
        // one-active-batch rule means no FULL_SNAPSHOT could have started behind it.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        openSession("CONTINUOUS", true);

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
    }

    @Test
    void leavesTheRequestStandingWhileASnapshotIsUploading() {
        // The request is what re-arms a clean retry if the snapshot dies before it commits (#87).
        // Clearing it here would leave the site on the old baseline with nothing pending.
        openSession("FULL_SNAPSHOT", true);

        assertEquals(Outcome.SNAPSHOT_IN_PROGRESS, service.cancel(SITE));
        assertEquals("snapshot-in-progress", Outcome.SNAPSHOT_IN_PROGRESS.status());
        verify(syncStateService, never()).cancelRebaseline(any());
    }

    @Test
    void aSnapshotTheSweeperWillReapDoesNotBlockCancellation() {
        // stageForResume deliberately leaves a dropped snapshot's batch IN_PROGRESS, so without a
        // liveness check a dead session would answer "still uploading" for up to the batch timeout.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        openSession("FULL_SNAPSHOT", false);

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
        assertFalse(service.isSnapshotSessionOpen(SITE));
    }

    @Test
    void anOpenSessionOfUnknownModeIsNotReportedAsAKnownSuccess() {
        // Batches opened before V47 (or by the previous release during a rolling deploy) carry no
        // mode: it may be the very snapshot being called off, so do not promise it was averted.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        openSession(null, true);

        assertEquals(Outcome.CLIENT_NOTIFIED, service.cancel(SITE));
        assertFalse(service.isSnapshotSessionOpen(SITE), "an unknown mode is not a confirmed snapshot");
    }

    @Test
    void reportsSnapshotInProgressEvenWhenNoRequestWasPending() {
        // Second operator, or a bootstrap/gap-recovery snapshot: "nothing to cancel" would be the
        // opposite conclusion about a session that is replacing the baseline right now.
        openSession("FULL_SNAPSHOT", true);

        assertEquals(Outcome.SNAPSHOT_IN_PROGRESS, service.cancel(SITE));
        verify(syncStateService, never()).cancelRebaseline(any());
    }

    @Test
    void reportsClientNotifiedWhenTheOrderWentOutButNoSessionOpenedYet() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_AFTER_NOTICE);
        noSession();

        assertEquals(Outcome.CLIENT_NOTIFIED, service.cancel(SITE));
        assertEquals("client-notified", Outcome.CLIENT_NOTIFIED.status());
    }

    @Test
    void reportsNotRequestedWhenNothingWasPendingAndNoSnapshotRuns() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.NOT_PENDING);
        noSession();

        assertEquals(Outcome.NOT_REQUESTED, service.cancel(SITE));
        assertEquals("not-requested", Outcome.NOT_REQUESTED.status());
    }

    @Test
    void exposesOnlyALiveSnapshotForTheSyncStateProjection() {
        openSession("FULL_SNAPSHOT", true);
        assertTrue(service.isSnapshotSessionOpen(SITE));

        verify(syncStateService, never()).cancelRebaseline(any());
    }
}
