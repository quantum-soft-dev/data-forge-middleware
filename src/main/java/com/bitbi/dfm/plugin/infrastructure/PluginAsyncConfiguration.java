package com.bitbi.dfm.plugin.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async configuration for plugin execution.
 * Provides a dedicated thread pool for plugin event dispatch.
 *
 * <p>Per FR-008, plugin execution must be isolated with 30-second timeout.
 * This configuration provides the thread pool for async execution.</p>
 */
@Configuration
@EnableAsync
public class PluginAsyncConfiguration {

    /**
     * Creates a dedicated executor for plugin execution.
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
        executor.setThreadNamePrefix("plugin-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
