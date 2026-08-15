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
 *       ends. All six fixed-delay ticks in this application therefore share one thread, and one of
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
 * <p>The virtual-thread setting itself is untouched: it still applies to the web layer and to
 * {@code @Async}. Only scheduling is pinned.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class SchedulingConfiguration {

    /**
     * The application's {@code TaskScheduler}, built from Boot's builder so every
     * {@code spring.task.scheduling.*} property keeps its documented meaning.
     *
     * @param builder Boot's scheduler builder, pre-populated from {@code spring.task.scheduling.*}
     * @return the scheduler backing every {@code @Scheduled} method and
     *         {@link com.bitbi.dfm.batch.application.BatchRetentionScheduler}'s dynamic cron
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
