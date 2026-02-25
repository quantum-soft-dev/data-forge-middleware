package com.bitbi.dfm.plugin.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async configuration for plugin execution.
 * Provides dedicated thread pools for plugin event dispatch.
 *
 * <p>Per FR-008, plugin execution must be isolated with 30-second timeout.
 * This configuration provides two separate thread pools:</p>
 * <ul>
 *   <li>{@code pluginDispatchExecutor} - for @Async dispatch orchestration</li>
 *   <li>{@code pluginExecutionExecutor} - for actual plugin.execute() calls</li>
 * </ul>
 *
 * <p><b>Thread Starvation Prevention:</b> Using separate executors ensures that
 * dispatch threads waiting on plugin execution don't block the threads that
 * actually run the plugins.</p>
 */
@Configuration
@EnableAsync
public class PluginAsyncConfiguration {

    /**
     * Creates executor for plugin dispatch orchestration (@Async methods).
     * These threads coordinate the dispatch and wait for plugin execution.
     * <ul>
     *   <li>Core pool size: 5 threads</li>
     *   <li>Max pool size: 10 threads</li>
     *   <li>Queue capacity: 50 tasks</li>
     *   <li>Rejection policy: CallerRunsPolicy (backpressure)</li>
     * </ul>
     */
    @Bean(name = "pluginExecutor")
    public Executor pluginExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("plugin-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Creates executor for actual plugin execution (plugin.execute() calls).
     * Separate from dispatch executor to prevent thread starvation.
     *
     * <p>Pool sizes are reduced since a semaphore limits concurrent SQL generation
     * to 2 operations — extra threads would be wasteful and consume stack memory.</p>
     * <ul>
     *   <li>Core pool size: 4 threads</li>
     *   <li>Max pool size: 8 threads</li>
     *   <li>Queue capacity: 50 tasks</li>
     *   <li>Rejection policy: CallerRunsPolicy (backpressure)</li>
     * </ul>
     */
    @Bean(name = "pluginExecutionExecutor")
    public Executor pluginExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Pool sizes reduced from 10/20/100 — with SqlGenerationService's semaphore
        // limiting concurrent SQL generation to 2, extra threads waste stack memory.
        // 4 core threads = 2 for SQL generation + 2 spare for non-SQL plugin operations.
        // Queue of 50 is sufficient: semaphore-blocked tasks release threads quickly
        // (timeout or acquire), so the queue rarely fills beyond a few entries.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("plugin-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
