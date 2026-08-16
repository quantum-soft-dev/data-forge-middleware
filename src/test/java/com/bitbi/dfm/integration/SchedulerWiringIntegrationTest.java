package com.bitbi.dfm.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #146 — the real context must resolve {@code @Scheduled} onto the application's pool.
 *
 * <p>The other two guards are synthetic on purpose: one builds its own context to time a blocking
 * tick, the other reads YAML and reflects over classes. Neither would notice the wiring changing
 * underneath them — a second {@code TaskScheduler} or {@code ScheduledExecutorService} bean, a
 * {@code SchedulingConfigurer} handing Spring a different one, or a test bean overriding ours
 * (the {@code test} profile allows bean-definition overriding) would put every scheduled method
 * back on a scheduler nobody sized, with both of them still green.</p>
 */
@DisplayName("Scheduler wiring in the real context (#146)")
class SchedulerWiringIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    @DisplayName("the injected TaskScheduler is the application's pool, and it is the only candidate")
    void shouldResolveScheduledMethodsOntoTheApplicationPool() {
        ThreadPoolTaskScheduler scheduler = assertInstanceOf(ThreadPoolTaskScheduler.class, taskScheduler,
                "@Scheduled must run on the pool SchedulingConfiguration declares (issue #146)");

        assertTrue(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize() > 1,
                "a pool of one is the failure this issue fixed");
        assertTrue(scheduler.getScheduledThreadPoolExecutor().getThreadFactory() != null);

        // Spring picks by type, so a second candidate of either type is what silently moves the
        // scheduled methods elsewhere.
        assertEquals(1, context.getBeanNamesForType(TaskScheduler.class).length,
                "exactly one TaskScheduler bean, or the resolution of @Scheduled becomes ambiguous");
        assertEquals(0, context.getBeanNamesForType(ScheduledExecutorService.class).length,
                "a ScheduledExecutorService bean would be preferred over the TaskScheduler");
        // Boot contributes its own observability configurer, which only decorates tasks. One of
        // ours would be the thing that could hand Spring a different scheduler.
        java.util.List<String> ownConfigurers =
                java.util.Arrays.stream(context.getBeanNamesForType(SchedulingConfigurer.class))
                        .filter(name -> context.getType(name) != null
                                && context.getType(name).getName().startsWith("com.bitbi.dfm"))
                        .toList();
        assertEquals(java.util.List.of(), ownConfigurers,
                "a SchedulingConfigurer of ours can substitute the scheduler without touching this bean");
    }
}
