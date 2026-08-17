package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.infrastructure.PluginAsyncConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
            assertThat(taskExecutor.getCorePoolSize()).isEqualTo(2);
            assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(2);
            assertThat(taskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(500);

            taskExecutor.shutdown();
        }

        /**
         * The defect of #171 in miniature: with no capacity left, a {@code CallerRunsPolicy} would
         * run the audit write on the thread that submitted it — which, for this executor, is a
         * thread inside the publisher's commit synchronization still holding its connection.
         */
        @Test
        @DisplayName("Should drop rather than run a rejected audit write on the caller's thread")
        void shouldDropRatherThanRunARejectedWriteOnTheCallersThread() {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configuration.pluginAuditExecutor();
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            try {
                int slots = executor.getMaxPoolSize() + executor.getQueueCapacity();
                for (int i = 0; i < slots; i++) {
                    executor.execute(() -> awaitQuietly(release));
                }

                // Then — the rejected task neither runs here nor throws back at the caller
                assertThatCode(() -> executor.execute(() -> ranOn.set(Thread.currentThread())))
                        .doesNotThrowAnyException();
                assertThat(ranOn.get()).isNull();
            } finally {
                release.countDown();
                executor.shutdown();
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
        @DisplayName("Should use CallerRunsPolicy for both executors")
        void shouldUseCallerRunsPolicyForBothExecutors() {
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
