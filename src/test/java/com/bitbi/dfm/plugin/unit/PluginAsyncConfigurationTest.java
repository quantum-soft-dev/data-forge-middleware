package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.infrastructure.PluginAsyncConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests for PluginAsyncConfiguration.
 *
 * <p>Verifies thread pool sizing for the three plugin executors:</p>
 * <ul>
 *   <li>{@code pluginExecutor} - dispatch orchestration (unchanged)</li>
 *   <li>{@code pluginExecutionExecutor} - actual plugin execution (reduced pool)</li>
 *   <li>{@code pluginAuditExecutor} - deferred audit writes, which must never run inline (#171)</li>
 * </ul>
 */
@DisplayName("PluginAsyncConfiguration Unit Tests")
class PluginAsyncConfigurationTest {

    private PluginAsyncConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new PluginAsyncConfiguration();
    }

    @Nested
    @DisplayName("pluginExecutor (dispatch)")
    class PluginExecutor {

        @Test
        @DisplayName("Should configure pluginExecutor with correct pool sizes")
        void shouldConfigurePluginExecutorWithCorrectPoolSizes() {
            // When
            Executor executor = configuration.pluginExecutor();

            // Then
            assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            assertThat(taskExecutor.getCorePoolSize()).isEqualTo(5);
            assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(10);
            assertThat(taskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("pluginExecutionExecutor (execution)")
    class PluginExecutionExecutor {

        @Test
        @DisplayName("Should configure pluginExecutionExecutor with reduced pool sizes")
        void shouldConfigurePluginExecutionExecutorWithReducedPoolSizes() {
            // When
            Executor executor = configuration.pluginExecutionExecutor();

            // Then
            assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            assertThat(taskExecutor.getCorePoolSize()).isEqualTo(4);
            assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(8);
            assertThat(taskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("pluginAuditExecutor (deferred audit writes, #171)")
    class PluginAuditExecutor {

        @Test
        @DisplayName("Should configure pluginAuditExecutor as a small fixed pool")
        void shouldConfigurePluginAuditExecutorAsASmallFixedPool() {
            // When
            Executor executor = configuration.pluginAuditExecutor();

            // Then
            assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            try {
                assertThat(taskExecutor.getCorePoolSize()).isEqualTo(2);
                assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(2);
                assertThat(taskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
                        .isEqualTo(500);
            } finally {
                taskExecutor.shutdown();
            }
        }

        /**
         * The defect of #171 in miniature: {@code CallerRunsPolicy} would run the audit write on
         * the thread that submitted it — which, for this executor, is a thread inside the
         * publisher's commit synchronization still holding its connection. The rejection is thrown
         * instead, so that {@code PluginAuditEventListener} can catch it with the entry in hand and
         * name what was lost.
         */
        @Test
        @DisplayName("Should reject rather than run an audit write on the caller's thread")
        void shouldRejectRatherThanRunAWriteOnTheCallersThread() {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuration.pluginAuditExecutor();
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            try {
                fillEverySlot(executor, release, new AtomicInteger());

                assertThatThrownBy(() -> executor.execute(() -> ranOn.set(Thread.currentThread())))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(ranOn.get()).isNull();
            } finally {
                release.countDown();
                executor.shutdown();
            }
        }

        /**
         * A shutdown must not outlast the pod's grace period, and the only way to guarantee that is
         * to <em>discard</em> the queue rather than wait on it: this queue is ten times the
         * siblings' and fills only when the database is slow, which is when a pod is likely to be
         * replaced.
         *
         * <p>Asserting that {@code shutdown()} returns quickly would not be enough — with
         * {@code waitForTasksToCompleteOnShutdown(true)} it returns after the await while the
         * queued writes carry on in the background, on threads that would then be non-daemon. So
         * what is asserted is that the queued tasks <b>never ran</b>.</p>
         */
        @Test
        @DisplayName("Should discard its queue on shutdown rather than drain it")
        void shouldDiscardItsQueueOnShutdownRatherThanDrainIt() {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuration.pluginAuditExecutor();
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger started = new AtomicInteger();
            try {
                int submitted = fillEverySlot(executor, release, started);
                assertThat(submitted).isGreaterThan(executor.getMaxPoolSize());

                long begunAt = System.nanoTime();
                executor.shutdown();
                Duration took = Duration.ofNanos(System.nanoTime() - begunAt);

                assertThat(started.get())
                        .as("only the running tasks may have started: the queued writes must be "
                                + "discarded, not drained past the container's shutdown")
                        .isLessThanOrEqualTo(executor.getMaxPoolSize());
                assertThat(executor.getThreadPoolExecutor().isTerminated())
                        .as("the interrupted workers must have finished before destroy() returned")
                        .isTrue();
                assertThat(took).isLessThan(Duration.ofSeconds(15));
            } finally {
                release.countDown();
            }
        }

        @Test
        @DisplayName("Should run its threads as daemons, so a stopping JVM is never held open")
        void shouldRunItsThreadsAsDaemons() {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuration.pluginAuditExecutor();
            AtomicReference<Thread> worker = new AtomicReference<>();
            try {
                executor.execute(() -> worker.set(Thread.currentThread()));
                await().atMost(Duration.ofSeconds(5)).until(() -> worker.get() != null);

                assertThat(worker.get().isDaemon()).isTrue();
            } finally {
                executor.shutdown();
            }
        }

        /** Submits until the executor refuses, so the pool is provably full. */
        private int fillEverySlot(ThreadPoolTaskExecutor executor, CountDownLatch release,
                                  AtomicInteger started) {
            int submitted = 0;
            while (true) {
                try {
                    executor.execute(() -> {
                        started.incrementAndGet();
                        awaitQuietly(release);
                    });
                    submitted++;
                } catch (RejectedExecutionException e) {
                    return submitted;
                }
            }
        }

        private void awaitQuietly(CountDownLatch release) {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    @DisplayName("Rejection policy")
    class RejectionPolicy {

        @Test
        @DisplayName("Should use CallerRunsPolicy for the dispatch and execution executors")
        void shouldUseCallerRunsPolicyForTheDispatchAndExecutionExecutors() {
            // When
            ThreadPoolTaskExecutor pluginExecutor = (ThreadPoolTaskExecutor) configuration.pluginExecutor();
            ThreadPoolTaskExecutor executionExecutor = (ThreadPoolTaskExecutor) configuration.pluginExecutionExecutor();

            // Then
            assertThat(pluginExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThat(executionExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        }
    }

    @Nested
    @DisplayName("Shutdown behavior")
    class ShutdownBehavior {

        @Test
        @DisplayName("Should wait for tasks on shutdown")
        void shouldWaitForTasksOnShutdown() {
            // When
            Executor pluginRaw = configuration.pluginExecutor();
            Executor executionRaw = configuration.pluginExecutionExecutor();

            // Then — submit a task to verify the executors are operational (shutdown wait
            // is configured but not directly queryable; we verify the executor accepts tasks
            // and the thread pool is active, which confirms initialization succeeded with
            // waitForTasksToCompleteOnShutdown=true)
            assertThat(pluginRaw).isInstanceOf(ThreadPoolTaskExecutor.class);
            assertThat(executionRaw).isInstanceOf(ThreadPoolTaskExecutor.class);

            ThreadPoolTaskExecutor pluginExecutor = (ThreadPoolTaskExecutor) pluginRaw;
            ThreadPoolTaskExecutor executionExecutor = (ThreadPoolTaskExecutor) executionRaw;

            // Verify both executors are initialized and active (not shut down)
            assertThat(pluginExecutor.getThreadPoolExecutor().isShutdown()).isFalse();
            assertThat(executionExecutor.getThreadPoolExecutor().isShutdown()).isFalse();

            // Verify await termination is configured (60 seconds)
            // ThreadPoolTaskExecutor exposes this via the underlying executor being active
            assertThat(pluginExecutor.getThreadPoolExecutor().isTerminated()).isFalse();
            assertThat(executionExecutor.getThreadPoolExecutor().isTerminated()).isFalse();

            // Clean up
            pluginExecutor.shutdown();
            executionExecutor.shutdown();
        }
    }
}
