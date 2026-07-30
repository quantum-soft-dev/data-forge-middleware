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
 * averted. Neither the flag nor "some batch is open" can answer that on its own: the flag survives
 * the whole FULL_SNAPSHOT session (the wipe runs in the commit transaction), and an open batch is
 * just as likely to be an ordinary delta session.
 */
class DeltaRebaselineCancellationServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final DeltaRebaselineCancellationService service =
            new DeltaRebaselineCancellationService(syncStateService, batchRepository);

    private void openSession(String sessionMode) {
        Batch batch = mock(Batch.class);
        when(batch.getSessionMode()).thenReturn(sessionMode);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.of(batch));
    }

    @Test
    void reportsCancelledWhenTheClientWasNeverToldAndNothingIsRunning() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.empty());

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
        assertEquals("cancelled", Outcome.CANCELLED.status());
    }

    @Test
    void reportsCancelledWhileAnOrdinaryDeltaSessionIsOpen() {
        // A CONTINUOUS session holds its batch IN_PROGRESS for hours (029: batch = session), and the
        // one-active-batch rule means no FULL_SNAPSHOT could have started behind it — warning about
        // an in-flight snapshot here would make `cancelled` unreachable for such sites.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        openSession("CONTINUOUS");

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
    }

    @Test
    void reportsSnapshotInProgressOnlyForAnOpenFullSnapshot() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_AFTER_NOTICE);
        openSession("FULL_SNAPSHOT");

        assertEquals(Outcome.SNAPSHOT_IN_PROGRESS, service.cancel(SITE));
        assertEquals("snapshot-in-progress", Outcome.SNAPSHOT_IN_PROGRESS.status());
    }

    @Test
    void reportsSnapshotInProgressEvenWhenTheFlagWasAlreadyCleared() {
        // Second operator (or a stale pill): the first cancellation cleared the flag, but the
        // snapshot it could not stop is still uploading — "nothing to cancel" would be the opposite
        // conclusion about the very same session.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.NOT_PENDING);
        openSession("FULL_SNAPSHOT");

        assertEquals(Outcome.SNAPSHOT_IN_PROGRESS, service.cancel(SITE));
    }

    @Test
    void reportsClientNotifiedWhenTheOrderWentOutButNoSessionOpenedYet() {
        // The client holds NEED_REBASELINE and is preparing its snapshot: no batch exists yet, so
        // nothing can be observed — and `cancelled` would promise a re-upload was averted.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_AFTER_NOTICE);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.empty());

        assertEquals(Outcome.CLIENT_NOTIFIED, service.cancel(SITE));
        assertEquals("client-notified", Outcome.CLIENT_NOTIFIED.status());
    }

    @Test
    void reportsNotRequestedWhenNothingWasPendingAndNoSnapshotRuns() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.NOT_PENDING);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.empty());

        assertEquals(Outcome.NOT_REQUESTED, service.cancel(SITE));
        assertEquals("not-requested", Outcome.NOT_REQUESTED.status());
    }

    @Test
    void alwaysClearsTheFlagFirstSoNoSecondSnapshotIsOrdered() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_AFTER_NOTICE);
        openSession("FULL_SNAPSHOT");

        service.cancel(SITE);

        verify(syncStateService).cancelRebaseline(SITE);
    }

    @Test
    void snapshotSessionProbeIgnoresBatchesWithoutARecordedMode() {
        // v1 leftovers and batches started before V47 carry no mode — they must not read as snapshots.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(RebaselineCancellation.CLEARED_BEFORE_NOTICE);
        openSession(null);

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
        assertFalse(service.isSnapshotSessionOpen(SITE));
    }

    @Test
    void exposesTheOpenSnapshotForTheSyncStateProjection() {
        // Drives the UI badge: the cancellation warning must survive the toast that reported it.
        openSession("FULL_SNAPSHOT");
        assertTrue(service.isSnapshotSessionOpen(SITE));

        verify(syncStateService, never()).cancelRebaseline(any());
    }
}
