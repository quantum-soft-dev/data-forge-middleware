package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.DeltaRebaselineCancellationService.Outcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #84 — cancelling a requested re-baseline must say whether the full snapshot was actually
 * averted. The flag alone cannot answer that: it survives for the whole FULL_SNAPSHOT session
 * (the wipe runs in the commit transaction, not at session start), so a cancellation issued while
 * the client is uploading clears a flag the running session no longer reads.
 */
class DeltaRebaselineCancellationServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final DeltaSyncStateService syncStateService = mock(DeltaSyncStateService.class);
    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final DeltaRebaselineCancellationService service =
            new DeltaRebaselineCancellationService(syncStateService, batchRepository);

    @Test
    void reportsCancelledWhenNoSessionIsOpen() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(true);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.empty());

        assertEquals(Outcome.CANCELLED, service.cancel(SITE));
        assertEquals("cancelled", Outcome.CANCELLED.status());
    }

    @Test
    void reportsSessionInProgressWhenTheSiteIsAlreadyIngesting() {
        // The open session may be the FULL_SNAPSHOT the operator is trying to call off; it keeps
        // its own re-baseline intent and still replaces the baseline when it commits. Reporting
        // this as a plain success would claim a re-upload was averted that was not.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(true);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.of(mock(Batch.class)));

        assertEquals(Outcome.SESSION_IN_PROGRESS, service.cancel(SITE));
        assertEquals("session-in-progress", Outcome.SESSION_IN_PROGRESS.status());
    }

    @Test
    void clearsTheFlagEvenWhileASessionIsRunning() {
        // A snapshot in flight does not make the cancellation pointless: once it ends (or drops),
        // the cleared flag keeps GetSyncState from ordering yet another full snapshot.
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(true);
        when(batchRepository.findActiveBySiteId(SITE)).thenReturn(Optional.of(mock(Batch.class)));

        service.cancel(SITE);

        verify(syncStateService).cancelRebaseline(SITE);
    }

    @Test
    void reportsNotRequestedWithoutLookingAtSessions() {
        when(syncStateService.cancelRebaseline(SITE)).thenReturn(false);

        assertEquals(Outcome.NOT_REQUESTED, service.cancel(SITE));
        assertEquals("not-requested", Outcome.NOT_REQUESTED.status());
        verify(batchRepository, never()).findActiveBySiteId(any());
    }
}
