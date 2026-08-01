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

    private final BatchParquetFinalizationService service;
    private final ThreadPoolExecutor pool;

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
            while (service.finalizeNext()) {
                // drain all currently retryable rows
            }
        } catch (RuntimeException e) {
            log.warn("Batch Parquet finalization drain failed; durable rows remain retryable: {}",
                    e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
