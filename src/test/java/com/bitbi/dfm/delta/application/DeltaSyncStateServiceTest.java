package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService.RebaselineCancellation;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * #8 — the sync-state service mirrors the submitted schema version so GetSyncState and SessionStart
 * validation see the version the server holds (previously site_sync_state.schema_version stayed 0).
 */
class DeltaSyncStateServiceTest {

    private static final UUID SITE = UUID.randomUUID();

    private final SiteSyncStateRepository repository = mock(SiteSyncStateRepository.class);
    private final DeltaSyncStateService service = new DeltaSyncStateService(repository);

    @Test
    void recordSchemaVersionPersistsAndIsReadBackByGetSyncState() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.empty());

        service.recordSchemaVersion(SITE, 3);

        ArgumentCaptor<SiteSyncState> saved = ArgumentCaptor.forClass(SiteSyncState.class);
        verify(repository).save(saved.capture());
        assertEquals(3, saved.getValue().getSchemaVersion());
    }

    @Test
    void getSyncStateReturnsStoredSchemaVersion() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordSchemaVersion(5);
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        assertEquals(5, service.getSyncState(SITE).schemaVersion());
    }

    @Test
    void getSyncStateDefaultsToZeroWhenNeverSynced() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.empty());
        assertEquals(0, service.getSyncState(SITE).schemaVersion());
        verify(repository, never()).save(any());
    }

    @Test
    void recordCheckpointAdvancesWhenHigher() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(50L);
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.recordCheckpoint(SITE, 80L);

        assertEquals(80L, state.getLastCheckpointSeq());
        verify(repository).save(state);
    }

    @Test
    void recordCheckpointIsMonotonicAndIgnoresRegression() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.recordCheckpoint(80L);
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.recordCheckpoint(SITE, 50L); // stale/concurrent rebuild

        assertEquals(80L, state.getLastCheckpointSeq(), "checkpoint pointer must not regress");
        verify(repository, never()).save(any());
    }

    // --- B2: persistent rebaseline/rebuild request flags ---

    @Test
    void getSyncStateReportsNeedRebaselineWhenFlagSet() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebaseline();
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        assertTrue(service.getSyncState(SITE).needRebaseline());
    }

    @Test
    void getSyncStateReportsProceedWhenFlagNotSet() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(SiteSyncState.initial(SITE)));
        assertFalse(service.getSyncState(SITE).needRebaseline());
    }

    @Test
    void requestRebaselineSetsFlagAndPersistsCreatingRowIfAbsent() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.empty());

        service.requestRebaseline(SITE);

        ArgumentCaptor<SiteSyncState> saved = ArgumentCaptor.forClass(SiteSyncState.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().isRebaselineRequested());
    }

    @Test
    void requestRebuildSetsFlagAndPersists() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        assertTrue(service.requestRebuild(SITE), "newly flagged request must report true");

        assertTrue(state.isRebuildRequested());
        verify(repository).save(state);
    }

    @Test
    void requestRebuildIsIdempotentWhenAlreadyFlagged() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebuild();
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        assertFalse(service.requestRebuild(SITE), "already-flagged request must report false");
        verify(repository, never()).save(any());
    }

    @Test
    void cancelRebaselineClearsFlagAndPersists() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebaseline();
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        assertEquals(RebaselineCancellation.CLEARED_BEFORE_NOTICE, service.cancelRebaseline(SITE),
                "a request the client was never told about is provably called off");

        assertFalse(state.isRebaselineRequested());
        verify(repository).save(state);
    }

    @Test
    void cancelRebaselineLeavesTheRestOfTheRowUntouched() {
        // Issue #84 acceptance: cancelling must leave site_sync_state exactly as it was before the
        // request — only the flag moves, so the client resumes ordinary delta from lastAppliedSeq.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.advanceWatermark(100L);
        state.recordCheckpoint(50L);
        state.recordSchemaVersion(3);
        state.requestRebaseline();
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.cancelRebaseline(SITE);

        assertEquals(100L, state.getLastAppliedSeq());
        assertEquals(50L, state.getLastCheckpointSeq());
        assertNotNull(state.getLastCheckpointAt());
        assertEquals(3, state.getSchemaVersion());
        assertFalse(service.getSyncState(SITE).needRebaseline(), "GetSyncState must answer PROCEED again");
    }

    @Test
    void cancelRebaselineIsNoOpWhenNothingPending() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(SiteSyncState.initial(SITE)));

        assertEquals(RebaselineCancellation.NOT_PENDING, service.cancelRebaseline(SITE),
                "cancelling with no pending request must report NOT_PENDING");
        verify(repository, never()).save(any());
    }

    @Test
    void cancelRebaselineIsNoOpWhenRowAbsent() {
        // Never-synced site: no row means PROCEED already — do not materialize one just to clear a flag.
        when(repository.findBySiteId(SITE)).thenReturn(Optional.empty());

        assertEquals(RebaselineCancellation.NOT_PENDING, service.cancelRebaseline(SITE));
        verify(repository, never()).save(any());
    }

    @Test
    void markRebaselineNotifiedStampsOnceAndIsForgottenOnCancel() {
        // #84 review: "the client has already been told to re-baseline" is what separates a
        // cancellation that provably worked from one the client may already be acting on.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebaseline();
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.markRebaselineNotified(SITE);
        assertNotNull(state.getRebaselineNotifiedAt());
        verify(repository).save(state);

        // GetSyncState is polled continuously — a second NEED_REBASELINE answer must not re-save.
        service.markRebaselineNotified(SITE);
        verify(repository, times(1)).save(state);

        assertEquals(RebaselineCancellation.CLEARED_AFTER_NOTICE, service.cancelRebaseline(SITE),
                "the client already had the order, so the cancellation may lose the race");
        assertNull(state.getRebaselineNotifiedAt(), "cancelling must forget the notification too");
    }

    @Test
    void markRebaselineNotifiedIsNoOpWithoutPendingRequest() {
        // No pending request means GetSyncState answered PROCEED — there is nothing to remember.
        SiteSyncState state = SiteSyncState.initial(SITE);
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.markRebaselineNotified(SITE);

        assertNull(state.getRebaselineNotifiedAt());
        verify(repository, never()).save(any());
    }

    @Test
    void resetForRebaselineForgetsTheNotification() {
        // The snapshot committed: the next request starts from a clean slate.
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebaseline();
        state.markRebaselineNotified();

        state.resetForRebaseline(41L);

        assertFalse(state.isRebaselineRequested());
        assertNull(state.getRebaselineNotifiedAt());
    }

    @Test
    void findSitesWithPendingRebuildDelegatesToRepository() {
        when(repository.findSiteIdsWithRebuildRequested()).thenReturn(java.util.List.of(SITE));
        assertEquals(java.util.List.of(SITE), service.findSitesWithPendingRebuild());
    }

    @Test
    void clearRebuildRequestedResetsFlag() {
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebuild();
        when(repository.findBySiteId(SITE)).thenReturn(Optional.of(state));

        service.clearRebuildRequested(SITE);

        assertFalse(state.isRebuildRequested());
        verify(repository).save(state);
    }

    @Test
    void clearRebuildRequestedIsNoOpWhenRowAbsent() {
        when(repository.findBySiteId(SITE)).thenReturn(Optional.empty());
        service.clearRebuildRequested(SITE);
        verify(repository, never()).save(any());
    }

    @Test
    void siteSyncStateUpdatesOnlyDirtyColumns() {
        // All mutators are load-modify-save on one row with no @Version: without @DynamicUpdate,
        // Hibernate flushes the FULL row, so an ingestion tx that loaded the row before a
        // concurrent requestRebaseline commit would write back the stale flag=false and silently
        // drop the acknowledged rebaseline (review r3). Dynamic updates confine each tx to the
        // columns it actually changed.
        assertTrue(SiteSyncState.class.isAnnotationPresent(org.hibernate.annotations.DynamicUpdate.class),
                "SiteSyncState must use @DynamicUpdate so concurrent single-field writers cannot "
                        + "clobber each other's columns (lost rebaseline/rebuild flags)");
    }

    @Test
    void resetForRebaselineClearsRebaselineFlag() {
        // The flag is consumed when the FULL_SNAPSHOT session actually starts
        // (DeltaRebaselineService.reset -> resetForRebaseline).
        SiteSyncState state = SiteSyncState.initial(SITE);
        state.requestRebaseline();

        state.resetForRebaseline(41L);

        assertFalse(state.isRebaselineRequested());
        assertEquals(41L, state.getLastAppliedSeq());
    }
}
