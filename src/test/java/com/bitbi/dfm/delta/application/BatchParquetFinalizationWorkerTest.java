package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The worker owns two properties that only show up under a real thread: it drains the queue until
 * it runs dry, and it stops claiming the moment shutdown begins.
 */
class BatchParquetFinalizationWorkerTest {

    private final BatchParquetFinalizationService service =
            mock(BatchParquetFinalizationService.class);

    @Test
    void drainsUntilTheQueueRunsDry() throws Exception {
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch dry = new CountDownLatch(1);
        when(service.finalizeNext()).thenAnswer(invocation -> {
            boolean more = claims.incrementAndGet() < 3;
            if (!more) {
                dry.countDown();
            }
            return more;
        });
        BatchParquetFinalizationWorker worker = new BatchParquetFinalizationWorker(service, 1);

        worker.wake();

        assertTrue(dry.await(5, TimeUnit.SECONDS), "the worker never drained the queue");
        Thread.sleep(50);
        assertEquals(3, claims.get(), "the drain stops at the first claim that finds nothing");
        worker.shutdown();
    }

    @Test
    void stopsClaimingOnceShutdownBegins() throws Exception {
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        // A queue that never runs dry: only the shutdown flag can end this drain.
        when(service.finalizeNext()).thenAnswer(invocation -> {
            claims.incrementAndGet();
            running.countDown();
            Thread.sleep(10);
            return true;
        });
        BatchParquetFinalizationWorker worker = new BatchParquetFinalizationWorker(service, 1);
        worker.wake();
        assertTrue(running.await(5, TimeUnit.SECONDS), "the worker never started draining");

        worker.shutdown();

        // A claim spends an attempt durably, so one taken on the way out would do no work and leave
        // its row BUILDING until the lease expires. shutdown() also has to wait for the build in
        // flight — returning early would tear down the lease-renewal executor underneath it.
        int afterShutdown = claims.get();
        Thread.sleep(100);
        assertEquals(afterShutdown, claims.get(), "the worker kept claiming after shutdown");
    }

    @Test
    void aFailedDrainLeavesTheWorkerUsable() throws Exception {
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch second = new CountDownLatch(2);
        when(service.finalizeNext()).thenAnswer(invocation -> {
            second.countDown();
            if (claims.incrementAndGet() == 1) {
                throw new IllegalStateException("claim transaction failed");
            }
            return false;
        });
        BatchParquetFinalizationWorker worker = new BatchParquetFinalizationWorker(service, 1);

        worker.wake();
        Thread.sleep(50);
        worker.wake();

        assertTrue(second.await(5, TimeUnit.SECONDS),
                "a drain that threw must not take the pool thread with it");
        worker.shutdown();
    }
}
