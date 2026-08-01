package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Makes batch artifact work durable before commit and starts processing only after commit. */
@Component
public class BatchParquetFinalizationListener {

    private final BatchParquetFinalizationService service;
    private final BatchParquetFinalizationWorker worker;

    public BatchParquetFinalizationListener(BatchParquetFinalizationService service,
                                            BatchParquetFinalizationWorker worker) {
        this.service = service;
        this.worker = worker;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void enqueue(BatchCompletedEvent event) {
        service.enqueueBatch(event.batchId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void wake(BatchCompletedEvent event) {
        worker.wake();
    }
}
