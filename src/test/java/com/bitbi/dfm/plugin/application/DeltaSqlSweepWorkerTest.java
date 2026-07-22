package com.bitbi.dfm.plugin.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeltaSqlSweepWorker} (026-bitbi-delta-sql, T6).
 */
@DisplayName("DeltaSqlSweepWorker")
class DeltaSqlSweepWorkerTest {

    @Test
    @DisplayName("wake() should drain until the queue reports empty")
    void wakeShouldDrainUntilEmpty() throws Exception {
        DeltaSqlQueueService queueService = mock(DeltaSqlQueueService.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        when(queueService.processNextPending()).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n >= 3) {
                done.countDown();
                return false;
            }
            return true;
        });

        DeltaSqlSweepWorker worker = new DeltaSqlSweepWorker(queueService, 1);
        worker.wake();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("drain failure should be swallowed (segment stays pending for the sweep)")
    void drainFailureIsSwallowed() throws Exception {
        DeltaSqlQueueService queueService = mock(DeltaSqlQueueService.class);
        CountDownLatch called = new CountDownLatch(1);
        when(queueService.processNextPending()).thenAnswer(inv -> {
            called.countDown();
            throw new RuntimeException("boom");
        });

        DeltaSqlSweepWorker worker = new DeltaSqlSweepWorker(queueService, 1);
        worker.wake();

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        // no exception escapes the pool thread; a subsequent wake still works
        worker.wake();
    }
}
