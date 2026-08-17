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
 * This configuration provides three separate thread pools:</p>
 * <ul>
 *   <li>{@code pluginExecutor} - for @Async dispatch orchestration</li>
 *   <li>{@code pluginExecutionExecutor} - for actual plugin.execute() calls</li>
 *   <li>{@code pluginAuditExecutor} - for the deferred audit writes of
 *       {@code PluginAuditEventListener}, which must never run inline (issue #171)</li>
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

    /**
     * Creates the executor that carries the deferred audit writes of
     * {@code PluginAuditEventListener} (issue #171).
     *
     * <p>A lane of its own, for two reasons that both come from the same fact: this listener runs
     * in the publisher's {@code afterCommit} synchronization, where the publishing thread still
     * holds its own connection ({@code ConnectionHolder} is released in {@code afterCompletion}).
     * On {@code pluginExecutor} a saturated pool would hand the write back to that thread through
     * {@code CallerRunsPolicy}, and the audit write's own {@code REQUIRES_NEW} would then ask for a
     * <b>second</b> connection while the first is held — the one hold-and-wait shape the pool
     * sizing of #161 rules out everywhere else. And the write would queue behind plugin dispatch
     * work that takes seconds, when it is one INSERT.</p>
     *
     * <ul>
     *   <li>Core and max pool size: 2 threads — one INSERT each, so two drain far more than the
     *       audit stream produces, while a single thread would serialize the whole trail behind one
     *       slow write</li>
     *   <li>Queue capacity: 500 tasks — deep enough that filling it means the database itself is
     *       unavailable, in which case the write would fail rather than queue</li>
     *   <li>Rejection policy: the default {@code AbortPolicy}, deliberately, and it is the one
     *       place the three pools' <em>rejection policies</em> differ. {@code CallerRunsPolicy} is
     *       the defect of #171. The listener submits explicitly rather than through {@code @Async}
     *       precisely so that it catches the rejection with the entry still in hand and can name it
     *       in the log; a policy that swallowed the task here would take that away and leave only
     *       the pool's statistics.</li>
     *   <li>Shutdown: the queue is <b>dropped</b>, the two in-flight writes are given
     *       <b>5 seconds</b>, and the threads are daemons. The siblings wait 60 s for a 50-deep
     *       queue; this queue is ten times that and fills only when the database is slow, which is
     *       exactly when a pod is being replaced — draining it would be 500 INSERTs against the
     *       thing that is already failing, and these entries are the ones this design has already
     *       declared droppable under pressure.
     *       <p>All three settings are load-bearing together, which is why waiting is not the
     *       conservative choice it looks like. {@code setWaitForTasksToCompleteOnShutdown(true)}
     *       means an <em>orderly</em> {@code shutdown()}: the whole queue still executes, and
     *       {@code awaitTerminationSeconds} only bounds how long {@code destroy()} blocks — it does
     *       not stop the work. Since {@code ThreadPoolTaskExecutor} threads are not daemons by
     *       default, those writes would then keep the JVM alive past the wait, which is the
     *       SIGKILL at {@code terminationGracePeriodSeconds} (30 in
     *       {@code k8s/base/deployment-backend.yaml}) that the bound is supposed to prevent.
     *       {@code false} makes it {@code shutdownNow()} — queue discarded, workers interrupted —
     *       and it also lets the executor take Spring's early stop signal, so it stops accepting
     *       before the {@code DataSource} closes rather than after.</p></li>
     * </ul>
     */
    @Bean(name = "pluginAuditExecutor")
    public Executor pluginAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("plugin-audit-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
