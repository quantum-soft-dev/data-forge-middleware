package com.bitbi.dfm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for asynchronous processing.
 *
 * <p>Provides dedicated thread pool for comparison operations to prevent
 * blocking the main application threads during large file comparisons.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Core pool size: 2 threads (minimum active threads)</li>
 *   <li>Max pool size: 5 threads (maximum concurrent comparisons)</li>
 *   <li>Queue capacity: 10 (pending comparisons before rejection)</li>
 *   <li>Thread name prefix: comparison-executor-</li>
 * </ul>
 *
 * <p>Usage in services:
 * <pre>
 * {@code
 * @Async("comparisonExecutor")
 * public CompletableFuture<ComparisonResponseDto> createComparisonAsync(...) {
 *     // Long-running comparison logic
 *     return CompletableFuture.completedFuture(result);
 * }
 * }
 * </pre>
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T061 - Add @Async support for large comparisons (>100 files)
 * Phase: Phase 4 - User Story 2 (Compare Files)
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

    /**
     * Creates dedicated executor for comparison operations.
     *
     * <p>This executor is used for async comparison processing to:
     * <ul>
     *   <li>Prevent blocking main application threads</li>
     *   <li>Control concurrent comparison operations (max 5)</li>
     *   <li>Queue up to 10 pending comparisons before rejecting</li>
     *   <li>Improve responsiveness for large file comparisons (>100 files)</li>
     * </ul>
     *
     * @return configured thread pool executor for comparison operations
     */
    @Bean(name = "comparisonExecutor")
    public Executor comparisonExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core pool size: minimum number of threads to keep alive
        executor.setCorePoolSize(2);

        // Max pool size: maximum number of concurrent comparison operations
        executor.setMaxPoolSize(5);

        // Queue capacity: number of pending tasks before rejection
        // With capacity 10, we can queue 10 comparisons before rejecting new requests
        executor.setQueueCapacity(10);

        // Thread name prefix for easier debugging in logs
        executor.setThreadNamePrefix("comparison-executor-");

        // Wait for tasks to complete on shutdown (graceful shutdown)
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // Maximum time to wait for tasks to complete during shutdown (30 seconds)
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * Single-thread executor for forced checkpoint rebuilds (feature 023, B7).
     *
     * <p>One thread serializes admin-triggered rebuilds (a checkpoint build can be heavy); a
     * small queue absorbs bursts across sites.</p>
     *
     * @return configured executor for forced checkpoint rebuilds
     */
    @Bean(name = "deltaRebuildExecutor")
    public Executor deltaRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("delta-rebuild-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
