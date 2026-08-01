package com.bitbi.dfm.delta.application;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker pool for durable completed-batch Parquet finalization. */
@Component
public class BatchParquetFinalizationWorker {

    private static final Logger log = LoggerFactory.getLogger(BatchParquetFinalizationWorker.class);

    private static final long SHUTDOWN_GRACE_SECONDS = 30L;

    private final BatchParquetFinalizationService service;
    private final ThreadPoolExecutor pool;
    private volatile boolean shuttingDown;

    public BatchParquetFinalizationWorker(
            BatchParquetFinalizationService service,
            @Value("${delta.batch-parquet.max-concurrent:2}") int maxConcurrent) {
        this.service = service;
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxConcurrent), runnable -> {
                    Thread thread = new Thread(runnable,
                            "batch-parquet-finalizer-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
    }

    public void wake() {
        pool.execute(this::drain);
    }

    @Scheduled(fixedDelayString = "${delta.batch-parquet.sweep-ms:60000}")
    public void sweep() {
        wake();
    }

    private void drain() {
        try {
            // Stop claiming once shutdown began: a claim spends an attempt, and a row claimed on
            // the way out would be built by nobody and sit BUILDING until its lease lapses.
            while (!shuttingDown && service.finalizeNext()) {
                // drain all currently retryable rows
            }
        } catch (RuntimeException e) {
            // A build failure is recorded on the row itself; what reaches here is a claim or
            // publish transaction failing, and that is only ever diagnosable from the stack trace.
            log.warn("Batch Parquet finalization drain failed; durable rows remain retryable", e);
        }
    }

    /**
     * Let an in-flight build finish before the context tears down the pieces it needs. Spring
     * destroys this bean before the service it depends on, so waiting here is what keeps a running
     * finalization from losing its lease-renewal executor mid-build.
     */
    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Batch Parquet finalization did not finish within {}s; the claimed rows stay "
                        + "BUILDING until their lease expires", SHUTDOWN_GRACE_SECONDS);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
