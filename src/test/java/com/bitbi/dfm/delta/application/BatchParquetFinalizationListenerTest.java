package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class BatchParquetFinalizationListenerTest {

    @Test
    void enqueuesBeforeWakingTheWorkerAfterCompletionCommits() {
        BatchParquetFinalizationService service = mock(BatchParquetFinalizationService.class);
        BatchParquetFinalizationWorker worker = mock(BatchParquetFinalizationWorker.class);
        BatchParquetFinalizationListener listener = new BatchParquetFinalizationListener(service, worker);
        UUID batchId = UUID.randomUUID();
        BatchCompletedEvent event = new BatchCompletedEvent(batchId, UUID.randomUUID(), 0, 0);

        listener.enqueueAndWake(event);

        org.mockito.InOrder order = inOrder(service, worker);
        order.verify(service).enqueueBatch(batchId);
        order.verify(worker).wake();
    }

    /**
     * The phase is the isolation contract, not a detail. Enqueue must not make an otherwise valid
     * ingestion commit depend on the manifest insert; lazy download backfill covers a callback
     * failure after the batch is durable.
     */
    @Test
    void enqueueAndWakeRunOnlyOnceTheBatchIsCommitted() {
        assertPhase("enqueueAndWake", TransactionPhase.AFTER_COMMIT);
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
