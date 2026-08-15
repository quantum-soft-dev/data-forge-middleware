package com.bitbi.dfm.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler every {@code @Scheduled} method runs on (issue #146).
 *
 * <p>Without this bean the scheduler is whatever Spring Boot's auto-configuration picks, and that
 * choice is decided by an unrelated global flag. With {@code spring.threads.virtual.enabled=true}
 * (set in {@code application.yml} for the web and async layers) Boot builds a
 * {@link org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler}; with the flag off it
 * builds a {@link ThreadPoolTaskScheduler} whose default pool size is <b>one</b>. Neither is a
 * scheduling decision this application ever made, and both leave a hole:</p>
 *
 * <ul>
 *   <li><b>Flag on.</b> Cron and fixed-rate ticks are handed off to a fresh virtual thread and are
 *       genuinely independent — but a <em>fixed-delay</em> tick runs on the scheduler's own single
 *       thread, because fixed-delay semantics require the next delay to start when the previous run
 *       ends. All eight fixed-delay ticks in this application therefore share one thread, and one of
 *       them blocking postpones the rest.</li>
 *   <li><b>Flag off.</b> Every tick shares one platform thread — the failure the ticket describes,
 *       where the nightly checkpoint build postpones the scratch sweep, the batch timeout sweep and
 *       the monthly partition creation.</li>
 * </ul>
 *
 * <p>Declaring the bean here makes Boot's {@code TaskSchedulerConfiguration} back off and, more to
 * the point, makes {@code spring.task.scheduling.pool.size} a knob that actually does something:
 * under virtual threads that key configures a builder whose product is never used, so adding it
 * alone would have looked like a fix and changed nothing. The builder is Boot's own, so the rest of
 * {@code spring.task.scheduling.*} (thread name prefix, shutdown behaviour, customizers) keeps
 * working as documented.</p>
 *
 * <p>Two properties come with the pool that the hand-off scheduler did not offer, and both are
 * wanted here: a task never overlaps <em>itself</em> ({@code ScheduledThreadPoolExecutor} will not
 * run two executions of the same periodic task concurrently), and the number of threads scheduled
 * work can occupy is bounded — it has to stay below the Hikari pool, or a burst of ticks could
 * starve request threads of database connections. The size and its derivation live next to the key
 * in {@code application.yml}; {@code ScheduledTaskInventoryTest} keeps them honest.</p>
 *
 * <p>The virtual-thread setting itself is untouched — it still applies to the web layer. It never
 * reached {@code @Async}: {@code AsyncConfiguration} and {@code PluginAsyncConfiguration} register
 * {@code Executor} beans, so Boot's virtual-thread {@code applicationTaskExecutor} had already
 * backed off and every {@code @Async} site names a platform-thread pool. Only scheduling changes
 * here.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class SchedulingConfiguration {

    /**
     * The application's {@code TaskScheduler}, built from Boot's builder so every
     * {@code spring.task.scheduling.*} property keeps its documented meaning, with two shutdown
     * settings fixed in code because they are properties of <em>these tasks</em> rather than of a
     * deployment — and because both of them silently changed when the scheduler did.
     *
     * <p><b>No interrupt on shutdown.</b> Boot defaults
     * {@code spring.task.scheduling.shutdown.await-termination} to false, which makes
     * {@code ExecutorConfigurationSupport} call {@code shutdownNow()} and interrupt whatever is
     * running. That is a change for the task where it matters most: a cron body such as the 02:00
     * checkpoint build ran on a virtual thread the previous scheduler never touched. Interrupting it
     * does not merely stop it — {@code CheckpointService} catches any {@code RuntimeException} per
     * table and records a failure that detaches the table's snapshot key on an advancing seq, so a
     * table would 404 for Bit BI and Parquet Export until a later rematerialize. A doomed build
     * should die with the process, not write a conclusion on the way out. (Fixed-delay bodies were
     * interrupted before, by {@code SimpleAsyncTaskScheduler.close()}; they now get the same
     * treatment as the rest.) Only the boolean is fixed;
     * {@code spring.task.scheduling.shutdown.await-termination-period} still applies if a
     * deployment wants shutdown to wait.</p>
     *
     * <p><b>Queued tasks are dropped anyway.</b> The flag above alone would keep the executor alive:
     * a plain {@code shutdown()} still runs already-queued <em>delayed</em> tasks, and Spring
     * schedules every cron tick as one ({@code ReschedulingRunnable}), so a context would not
     * terminate until the furthest cron came due — a month away, for the monthly partition job —
     * leaving its threads parked, its ticks firing against a closed context, and any
     * {@code await-termination-period} certain to time out. Dropping the delayed queue keeps the
     * no-interrupt property and lets the executor terminate as soon as what is running finishes.</p>
     *
     * <p><b>Daemon threads.</b> The corollary of not interrupting: non-daemon pool threads would
     * hold the JVM open after the context closes until the grace period ran out and SIGKILL landed.
     * Virtual threads are always daemon, so this restores what the previous scheduler gave.</p>
     *
     * <p><b>The one difference left</b> is the window between {@code ContextClosedEvent} and this
     * bean's destruction: waiting for tasks means Spring skips the early-stop signal, so a tick due
     * inside that window still starts, where the previous scheduler stopped triggering at the event.
     * It is bounded by the lifecycle stop phase and costs at worst a log line and a rolled-back
     * transaction — every scheduled method here either catches its own failure or is caught by the
     * scheduler's log-and-suppress error handler — which is a smaller price than the detached
     * snapshot the interrupt buys.</p>
     *
     * @param builder Boot's scheduler builder, pre-populated from {@code spring.task.scheduling.*}
     * @return the scheduler backing every {@code @Scheduled} method and
     *         {@link com.bitbi.dfm.batch.application.BatchRetentionScheduler}'s dynamic cron
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        ThreadPoolTaskScheduler scheduler = builder.build();
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setDaemon(true);
        return scheduler;
    }
}
