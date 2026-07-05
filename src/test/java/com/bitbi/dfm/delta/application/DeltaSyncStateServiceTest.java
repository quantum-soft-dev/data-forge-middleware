package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        service.requestRebuild(SITE);

        assertTrue(state.isRebuildRequested());
        verify(repository).save(state);
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
