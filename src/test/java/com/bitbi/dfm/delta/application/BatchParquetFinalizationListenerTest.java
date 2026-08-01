package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BatchParquetFinalizationListenerTest {

    @Test
    void enqueuesBeforeCommitAndWakesAfterCommit() {
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
}
