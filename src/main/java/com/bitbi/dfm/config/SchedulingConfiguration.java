package com.bitbi.dfm.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
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
 * work can occupy is bounded, which matters because nearly every tick opens a database connection.
 * The size, its derivation and what the bound does and does not promise live next to the key in
 * {@code application.yml}; {@code ScheduledTaskInventoryTest} keeps them honest.</p>
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
     * {@code spring.task.scheduling.*} property keeps its documented meaning, with the shutdown
     * behaviour fixed in code because it is a property of <em>these tasks</em> rather than of a
     * deployment — and because it silently changed when the scheduler did.
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
     * <p><b>Stop triggering at {@code ContextClosedEvent}.</b> Waiting for tasks also opts the
     * scheduler out of Spring's early-stop signal, which would leave it triggering new ticks until
     * this bean's own destruction — past the {@code @PreDestroy} of its peers, so a tick could open a
     * transaction on a closed {@code DataSource}. Annotated tasks are cancelled during bean
     * destruction, but {@code BatchRetentionScheduler}'s programmatic cron is cancelled by nobody, so
     * that window is real. {@link EarlyShutdownTaskScheduler} closes it by shutting the executor down
     * when the event arrives, which under the settings above means "start nothing new, interrupt
     * nothing running" — the behaviour {@code SimpleAsyncTaskScheduler} had, and the reason the two
     * settings can be kept.</p>
     *
     * @param builder Boot's scheduler builder, pre-populated from {@code spring.task.scheduling.*}
     * @return the scheduler backing every {@code @Scheduled} method and
     *         {@link com.bitbi.dfm.batch.application.BatchRetentionScheduler}'s dynamic cron
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        ThreadPoolTaskScheduler scheduler = builder.configure(new EarlyShutdownTaskScheduler());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setDaemon(true);
        return scheduler;
    }

    /**
     * A {@link ThreadPoolTaskScheduler} that stops triggering when the context closes instead of
     * when it is destroyed.
     *
     * <p>{@code ExecutorConfigurationSupport} couples the two things this application wants apart: a
     * scheduler that waits for its tasks takes the "late shutdown" branch, which skips
     * {@code initiateEarlyShutdown()} and makes {@code stop()} a no-op, while the branch that does
     * stop early shuts down by interrupting. Calling {@code initiateShutdown()} from the event gives
     * the missing combination — it honours {@code waitForTasksToCompleteOnShutdown}, so it is a plain
     * {@code shutdown()}: the queue is dropped, nothing running is interrupted, and no further tick
     * starts. Destruction then calls {@code shutdown()} again, which is a no-op on an executor
     * already shut down.</p>
     */
    static final class EarlyShutdownTaskScheduler extends ThreadPoolTaskScheduler {

        /** The context this scheduler belongs to; a parent's close must not shut it down. */
        private transient ApplicationContext ownContext;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) {
            super.setApplicationContext(applicationContext);
            this.ownContext = applicationContext;
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            super.onApplicationEvent(event);
            if (event.getApplicationContext() == this.ownContext) {
                initiateShutdown();
            }
        }
    }
}
