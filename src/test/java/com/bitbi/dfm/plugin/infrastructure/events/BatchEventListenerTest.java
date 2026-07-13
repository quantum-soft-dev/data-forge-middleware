package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.application.PluginEventDispatcher;
import com.bitbi.dfm.plugin.domain.PluginEvent;
import com.bitbi.dfm.plugin.domain.PluginEventType;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link BatchEventListener}.
 *
 * <p>The handlers must run AFTER_COMMIT: {@code DeltaSessionCommitService.commit} completes the
 * batch inside its own transaction, and the async plugin dispatch must not start before the
 * changelog segment row and COMPLETED status are visible. {@code fallbackExecution = true}
 * preserves dispatch for non-transactional publishers.</p>
 */
@ExtendWith(MockitoExtension.class)
class BatchEventListenerTest {

    @Mock
    private PluginEventDispatcher pluginEventDispatcher;

    @InjectMocks
    private BatchEventListener listener;

    @Test
    @DisplayName("onBatchCompleted should be a TransactionalEventListener bound to AFTER_COMMIT with fallback execution")
    void shouldDispatchBatchCompletedAfterCommit() throws NoSuchMethodException {
        assertAfterCommitListener(BatchEventListener.class.getMethod("onBatchCompleted", BatchCompletedEvent.class));
    }

    @Test
    @DisplayName("onBatchExpired should be a TransactionalEventListener bound to AFTER_COMMIT with fallback execution")
    void shouldDispatchBatchExpiredAfterCommit() throws NoSuchMethodException {
        assertAfterCommitListener(BatchEventListener.class.getMethod("onBatchExpired", BatchExpiredEvent.class));
    }

    private static void assertAfterCommitListener(Method handler) {
        TransactionalEventListener annotation = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation)
                .as("%s must use @TransactionalEventListener (plain @EventListener races the committing transaction)",
                        handler.getName())
                .isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
                .as("non-transactional publishers (tests, schedulers) must still dispatch")
                .isTrue();
    }

    @Test
    @DisplayName("Should dispatch BATCH_COMPLETED plugin event when accountId present")
    void shouldDispatchWhenAccountIdPresent() {
        UUID batchId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        listener.onBatchCompleted(new BatchCompletedEvent(batchId, accountId, 3, 1024L));

        ArgumentCaptor<PluginEvent> captor = ArgumentCaptor.forClass(PluginEvent.class);
        verify(pluginEventDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(PluginEventType.BATCH_COMPLETED);
        assertThat(captor.getValue().accountId()).isEqualTo(accountId);
        assertThat(captor.getValue().resourceId()).isEqualTo(batchId);
    }

    @Test
    @DisplayName("Should skip dispatch when accountId missing")
    void shouldSkipDispatchWhenAccountIdMissing() {
        listener.onBatchCompleted(new BatchCompletedEvent(UUID.randomUUID(), null, 3, 1024L));

        verify(pluginEventDispatcher, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }
}
