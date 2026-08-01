package com.bitbi.dfm.delta.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bitbi.dfm.util.LogCapture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The worker owns three properties that only show up under a real thread: it drains the queue until
 * it runs dry, it stops claiming the moment shutdown begins, and a drain that blows up is reported
 * with enough detail to act on.
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

        long startedAt = System.nanoTime();
        worker.shutdown();
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        // The elapsed time is the assertion that matters. Without the `!shuttingDown` guard the
        // drain keeps claiming until the 30s grace runs out and the pool is force-terminated —
        // the claim counter would settle either way, so only the promptness distinguishes them.
        // A claim spends an attempt durably, so one taken on the way out does no work and leaves
        // its row BUILDING until the lease expires.
        assertTrue(took.toSeconds() < 5,
                "shutdown waited " + took.toMillis() + "ms: the drain kept claiming until the grace expired");
        int afterShutdown = claims.get();
        Thread.sleep(100);
        assertEquals(afterShutdown, claims.get(), "the worker kept claiming after shutdown");
    }

    @Test
    void reportsADrainFailureWithItsStackTrace() throws Exception {
        when(service.finalizeNext()).thenThrow(new IllegalStateException("claim transaction failed"));
        BatchParquetFinalizationWorker worker = new BatchParquetFinalizationWorker(service, 1);

        try (LogCapture capture = LogCapture.attachTo(BatchParquetFinalizationWorker.class)) {
            worker.wake();

            ILoggingEvent failure = awaitEvent(capture);
            assertEquals(Level.WARN, failure.getLevel());
            // A build failure is recorded on the row itself; a claim or publish transaction failing
            // surfaces only here, and the bare message is not enough to act on.
            assertNotNull(failure.getThrowableProxy(), "the drain failure was logged without its cause");
        } finally {
            worker.shutdown();
        }
    }

    private static ILoggingEvent awaitEvent(LogCapture capture) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!capture.events().isEmpty()) {
                return capture.events().get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the worker never reported the failed drain");
    }
}
