package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
