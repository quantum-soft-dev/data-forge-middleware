package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BatchParquetFinalizationListenerTest {

    @Test
    void delegatesTheCompletionToTheQueueAndTheWorker() {
        BatchParquetFinalizationService service = mock(BatchParquetFinalizationService.class);
        BatchParquetFinalizationWorker worker = mock(BatchParquetFinalizationWorker.class);
        BatchParquetFinalizationListener listener = new BatchParquetFinalizationListener(service, worker);
        UUID batchId = UUID.randomUUID();
        BatchCompletedEvent event = new BatchCompletedEvent(batchId, UUID.randomUUID(), 0, 0);

        listener.enqueue(event);
        listener.wake(event);

        verify(service).enqueueBatch(batchId);
        verify(worker).wake();
    }

    /**
     * The phases are the durability contract, not a detail. {@code BEFORE_COMMIT} is what puts the
     * manifest rows in the same transaction as the completion: move the enqueue to
     * {@code AFTER_COMMIT} or a plain {@code @EventListener} and a batch could commit as completed
     * with no work rows behind it — or gain rows for a completion that then rolled back — while
     * every behavioural test stays green.
     */
    @Test
    void enqueueRunsInsideTheCompletingTransaction() {
        assertPhase("enqueue", TransactionPhase.BEFORE_COMMIT);
    }

    /** The wake must not fire before the rows are visible to the worker's own transaction. */
    @Test
    void wakeRunsOnlyOnceTheRowsAreCommitted() {
        assertPhase("wake", TransactionPhase.AFTER_COMMIT);
    }

    private static void assertPhase(String methodName, TransactionPhase expected) {
        Method handler;
        try {
            handler = BatchParquetFinalizationListener.class
                    .getDeclaredMethod(methodName, BatchCompletedEvent.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("BatchParquetFinalizationListener." + methodName + " is gone — if the "
                    + "handler was renamed, update this test rather than deleting it: the phase is what "
                    + "keeps the manifest and the batch completion in one transaction", e);
        }
        TransactionalEventListener annotation = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation)
                .describedAs("%s must stay a @TransactionalEventListener", methodName)
                .isNotNull();
        assertThat(annotation.phase()).isEqualTo(expected);
    }
}
