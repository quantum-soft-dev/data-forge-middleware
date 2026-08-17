package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Which of the two entries a deferred audit event resolves to, when, and on what kind of thread.
 */
@DisplayName("PluginAuditEventListener")
class PluginAuditEventListenerTest {

    private static final String PLUGIN_ID = "bit-bi";

    private PluginAuditEntryWriter writer;
    private PluginAuditEventListener listener;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        writer = mock(PluginAuditEntryWriter.class);
        // Runs the hand-off on this thread: what the executor does with the task is
        // PluginAsyncConfigurationTest's subject, so these tests need only the decision.
        listener = new PluginAuditEventListener(writer, Runnable::run);
        accountId = UUID.randomUUID();
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
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
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).isSameAs(entry);
    }

    @Test
    @DisplayName("a rolled back transaction persists the rollback entry instead")
    void shouldPersistRollbackEntryAfterRollback() {
        PluginAuditLog rollbackEntry = failure();

        listener.onAuditEntryRolledBack(new PluginAuditEntryReadyEvent(success(), rollbackEntry));

        ArgumentCaptor<PluginAuditLog> captor = ArgumentCaptor.forClass(PluginAuditLog.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).isSameAs(rollbackEntry);
    }

    @Test
    @DisplayName("a rollback of a wholly transactional change writes nothing")
    void shouldWriteNothingWhenRollbackUndidEverything() {
        listener.onAuditEntryRolledBack(new PluginAuditEntryReadyEvent(success()));

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("a rejected hand-off never escapes, and names the entry it lost (#171)")
    void shouldSwallowARejectedHandOff() {
        Executor full = task -> {
            throw new RejectedExecutionException("no capacity");
        };
        PluginAuditEventListener saturated = new PluginAuditEventListener(writer, full);

        assertThatCode(() -> saturated.onAuditEntryReady(new PluginAuditEntryReadyEvent(success())))
                .doesNotThrowAnyException();

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("a failed audit write never escapes — auditing must not break what it records")
    void shouldSwallowWriteFailures() {
        doThrow(new RuntimeException("partition missing")).when(writer).write(any());

        assertThatCode(() -> listener.onAuditEntryReady(new PluginAuditEntryReadyEvent(success())))
                .doesNotThrowAnyException();
    }

    /**
     * Issue #171. A thread inside a transaction still holds its connection — {@code afterCommit}
     * runs before the {@code ConnectionHolder} is unbound — so writing there would ask the pool for
     * a second one. The write is dropped instead of taking that risk, and the drop must not throw
     * either: it happens on the publisher's own thread, past the commit point.
     */
    @Test
    @DisplayName("a thread that already holds a connection writes nothing (#171)")
    void shouldNotWriteOnAThreadInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(() -> listener.onAuditEntryReady(new PluginAuditEntryReadyEvent(success())))
                .doesNotThrowAnyException();

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("the rollback entry is refused on the publishing thread for the same reason (#171)")
    void shouldNotWriteTheRollbackEntryOnAThreadInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        listener.onAuditEntryRolledBack(new PluginAuditEntryReadyEvent(success(), failure()));

        verifyNoInteractions(writer);
    }
}
