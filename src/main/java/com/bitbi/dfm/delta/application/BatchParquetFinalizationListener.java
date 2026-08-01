package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Enqueues completed-batch artifacts and starts processing after the ingestion commit. */
@Component
public class BatchParquetFinalizationListener {

    private static final Logger log = LoggerFactory.getLogger(BatchParquetFinalizationListener.class);

    private final BatchParquetFinalizationService service;
    private final BatchParquetFinalizationWorker worker;

    public BatchParquetFinalizationListener(BatchParquetFinalizationService service,
                                            BatchParquetFinalizationWorker worker) {
        this.service = service;
        this.worker = worker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void enqueueAndWake(BatchCompletedEvent event) {
        try {
            service.enqueueBatch(event.batchId());
            worker.wake();
        } catch (RuntimeException e) {
            // Completion is already durable. Lazy download backfill can recreate missing work, so
            // this best-effort callback must not make the client observe a false SessionEnd failure.
            log.error("Could not enqueue unified batch Parquet after batch {} committed; "
                    + "lazy download backfill remains available", event.batchId(), e);
        }
    }
}
