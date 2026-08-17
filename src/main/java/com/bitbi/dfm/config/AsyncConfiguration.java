package com.bitbi.dfm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Turns on {@code @Async} for the application, and declares the forced checkpoint rebuild pool.
 *
 * <p>{@link EnableAsync} is here <em>and</em> on {@code PluginAsyncConfiguration}, which is worth
 * knowing before reading either as load-bearing: every {@code @Async} site in the application is an
 * {@code @Async("pluginExecutor")} method in the plugin package, so that one is what actually
 * enables them, and the annotation is idempotent (Spring registers a single
 * {@code AsyncAnnotationBeanPostProcessor} however many configurations ask for it). This one is
 * therefore redundant today, and is kept as the application-wide declaration rather than leaving
 * async proxying to depend on a configuration in the plugin package.</p>
 *
 * <p>The one pool declared here is {@link #deltaRebuildExecutor()}. It is <em>not</em> reached
 * through {@code @Async}: {@code DeltaCheckpointRebuildService} injects it by
 * {@code @Qualifier("deltaRebuildExecutor")} and submits to it directly.</p>
 *
 * <p><b>A pool added here is background demand on the connection pool</b>, which is smaller than
 * the threads that can ask it for a connection (issue #161). {@code BackgroundConnectionDemandTest}
 * discovers every {@code @Bean} returning an {@link Executor} and fails until the newcomer is
 * classified there — including a pool with no caller at all, which is how
 * {@code comparisonExecutor} (declared in feature 009, orphaned when its only {@code @Async} method
 * was deleted before that feature shipped) was found and removed by issue #165.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

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
