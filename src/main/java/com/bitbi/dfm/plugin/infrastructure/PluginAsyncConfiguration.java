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
     * <ul>
     *   <li>Core pool size: 10 threads</li>
     *   <li>Max pool size: 20 threads</li>
     *   <li>Queue capacity: 100 tasks</li>
     *   <li>Rejection policy: CallerRunsPolicy (backpressure)</li>
     * </ul>
     */
    @Bean(name = "pluginExecutionExecutor")
    public Executor pluginExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("plugin-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
