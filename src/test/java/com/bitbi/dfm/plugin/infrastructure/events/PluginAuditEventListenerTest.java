package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Which of the two entries a deferred audit event resolves to, and when.
 */
@DisplayName("PluginAuditEventListener")
class PluginAuditEventListenerTest {

    private static final String PLUGIN_ID = "bit-bi";

    private PluginAuditLogRepository repository;
    private PluginAuditEventListener listener;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        repository = mock(PluginAuditLogRepository.class);
        listener = new PluginAuditEventListener(repository);
        accountId = UUID.randomUUID();
    }

    private PluginAuditLog success() {
        return PluginAuditLog.success(PLUGIN_ID, accountId, PluginActionType.PLUGIN_HISTORY_CLEARED);
    }

    private PluginAuditLog failure() {
        return PluginAuditLog.failure(PLUGIN_ID, accountId, PluginActionType.PLUGIN_HISTORY_CLEARED,
                "files gone, rows restored");
    }

    @Test
    @DisplayName("a committed transaction persists the entry")
    void shouldPersistEntryAfterCommit() {
        PluginAuditLog entry = success();

        listener.onAuditEntryReady(new PluginAuditEntryReadyEvent(entry, failure()));

        ArgumentCaptor<PluginAuditLog> captor = ArgumentCaptor.forClass(PluginAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(entry);
    }

    @Test
    @DisplayName("a rolled back transaction persists the rollback entry instead")
    void shouldPersistRollbackEntryAfterRollback() {
        PluginAuditLog rollbackEntry = failure();

        listener.onAuditEntryRolledBack(new PluginAuditEntryReadyEvent(success(), rollbackEntry));

        ArgumentCaptor<PluginAuditLog> captor = ArgumentCaptor.forClass(PluginAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(rollbackEntry);
    }

    @Test
    @DisplayName("a rollback of a wholly transactional change writes nothing")
    void shouldWriteNothingWhenRollbackUndidEverything() {
        listener.onAuditEntryRolledBack(new PluginAuditEntryReadyEvent(success()));

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a failed audit write never escapes — auditing must not break what it records")
    void shouldSwallowRepositoryFailures() {
        doThrow(new RuntimeException("partition missing")).when(repository).save(any());

        assertThatCode(() -> listener.onAuditEntryReady(new PluginAuditEntryReadyEvent(success())))
                .doesNotThrowAnyException();
    }
}
