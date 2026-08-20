package com.bitbi.dfm.plugin.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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

        DeltaSqlSweepWorker worker = new DeltaSqlSweepWorker(queueService, 1, 60_000L);
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

        DeltaSqlSweepWorker worker = new DeltaSqlSweepWorker(queueService, 1, 60_000L);
        worker.wake();

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        // no exception escapes the pool thread; a subsequent wake still works
        worker.wake();
    }

    /**
     * The two {@code plugin.sql-generation.*} keys this worker consumes fail fast (issue #185),
     * the same contract {@code SqlGenerationServiceTest} pins for the three keys of the
     * generation service — together the five make the whole block validated. Without it,
     * {@code delta-max-concurrent: 0} crash-looped through
     * {@code new ArrayBlockingQueue<>(0)}'s message-less {@code IllegalArgumentException} —
     * naming neither key nor value, the exact failure shape fail-fast exists to replace — and
     * {@code delta-sweep-ms: 0} was <em>accepted</em> by Spring and busy-looped the fallback
     * sweep on a green rollout.
     */
    @Nested
    @DisplayName("Configuration validation (issue #185)")
    class ConfigurationValidation {

        static Stream<Arguments> outOfRangeConfigurations() {
            return Stream.of(
                    arguments("plugin.sql-generation.delta-max-concurrent", 0, 60_000L, 0L),
                    arguments("plugin.sql-generation.delta-max-concurrent", -1, 60_000L, -1L),
                    arguments("plugin.sql-generation.delta-sweep-ms", 2, 0L, 0L),
                    arguments("plugin.sql-generation.delta-sweep-ms", 2, -5L, -5L));
        }

        @ParameterizedTest(name = "should refuse {0} = {3} at startup, naming the key and the value")
        @MethodSource("outOfRangeConfigurations")
        void shouldRefuseOutOfRangeValue(String key, int maxConcurrent, long sweepMillis,
                                         long offendingValue) {
            assertThatThrownBy(() ->
                    new DeltaSqlSweepWorker(mock(DeltaSqlQueueService.class), maxConcurrent, sweepMillis))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("but was " + offendingValue);
        }

        @Test
        @DisplayName("should accept both floors: one worker thread, a one-millisecond sweep")
        void shouldAcceptFloors() {
            assertThatCode(() -> new DeltaSqlSweepWorker(mock(DeltaSqlQueueService.class), 1, 1L))
                    .doesNotThrowAnyException();
        }
    }
}
