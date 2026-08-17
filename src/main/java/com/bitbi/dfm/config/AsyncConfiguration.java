package com.bitbi.dfm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Turns on {@code @Async} for the application, and declares the forced checkpoint rebuild pool.
 *
 * <p>{@link EnableAsync} lives here rather than beside any one pool: it is what makes every
 * {@code @Async("...")} site in the application work, including the ones whose executors are
 * declared elsewhere ({@code PluginAsyncConfiguration}). Removing it would not fail the build — the
 * annotated methods would silently start running on the caller's thread.</p>
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
