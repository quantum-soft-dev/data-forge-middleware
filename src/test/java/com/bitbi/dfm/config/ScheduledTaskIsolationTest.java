package com.bitbi.dfm.config;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #146 — a scheduled task that blocks must not postpone its neighbours.
 *
 * <p>The three cases below are the whole argument for {@link SchedulingConfiguration}, run against
 * the production property set ({@code spring.threads.virtual.enabled=true} plus the pool size this
 * repository ships in {@code application.yml}):</p>
 *
 * <ol>
 *   <li><b>Without</b> the bean, Spring Boot's auto-configuration hands back a
 *       {@link SimpleAsyncTaskScheduler} because virtual threads are enabled — and that scheduler
 *       runs <em>fixed-delay</em> tasks on one internal thread by design (fixed-delay semantics
 *       need the previous run to finish before the next delay starts). One blocking fixed-delay
 *       tick therefore stops every other fixed-delay tick, and
 *       {@code spring.task.scheduling.pool.size} does nothing about it: that key configures the
 *       {@code ThreadPoolTaskScheduler} which is not the bean in use.</li>
 *   <li>With the bean, a blocking fixed-delay tick leaves its neighbour running.</li>
 *   <li>With the bean, a blocking <em>cron</em> tick — the nightly checkpoint build in the
 *       ticket — leaves the fixed-delay scratch sweep running.</li>
 * </ol>
 */
@DisplayName("Scheduled task isolation (#146)")
class ScheduledTaskIsolationTest {

    /** Tick period of both fixtures; short enough that a few ticks fit in the assertion window. */
    private static final long TICK_MS = 100L;

    /** Upper bound on how long the hog holds its thread if a test fails to release it. */
    private static final long HOG_BUDGET_SECONDS = 20L;

    /** Ticks the neighbour must complete <em>after</em> the hog is blocked to count as running. */
    private static final int TICKS_PROVING_PROGRESS = 3;

    private static final Duration PROGRESS_WINDOW = Duration.ofSeconds(5);

    /** How long a stalled neighbour is watched before concluding it really is stalled. */
    private static final Duration STALL_WINDOW = Duration.ofSeconds(2);

    @Test
    @DisplayName("Boot's default scheduler under virtual threads serializes fixed-delay ticks")
    void shouldShowThatTheAutoConfiguredSchedulerSerializesFixedDelayTicks() {
        runner()
                .withUserConfiguration(FixedDelayHogConfiguration.class)
                .run(context -> {
                    assertInstanceOf(SimpleAsyncTaskScheduler.class, context.getBean(TaskScheduler.class),
                            "virtual threads select SimpleAsyncTaskScheduler, so the pool-size key is inert");

                    Hog hog = context.getBean(Hog.class);
                    Neighbour neighbour = context.getBean(Neighbour.class);
                    try {
                        hog.awaitStarted();
                        int base = neighbour.ticks();
                        Thread.sleep(STALL_WINDOW.toMillis());

                        assertEquals(base, neighbour.ticks(),
                                "the neighbour is expected to be stuck behind the hog on the single "
                                        + "fixed-delay thread — if it advanced, the framework changed and "
                                        + "the reasoning in SchedulingConfiguration needs re-reading");
                    } finally {
                        hog.release();
                    }
                });
    }

    @Test
    @DisplayName("The application scheduler keeps a neighbour running while a fixed-delay tick blocks")
    void shouldRunNeighbourWhileFixedDelayTickBlocks() {
        runner()
                .withUserConfiguration(SchedulingConfiguration.class, FixedDelayHogConfiguration.class)
                .run(context -> {
                    assertInstanceOf(ThreadPoolTaskScheduler.class, context.getBean(TaskScheduler.class),
                            "the application owns its scheduler so the pool size is a real knob");

                    assertNeighbourKeepsTicking(context.getBean(Hog.class), context.getBean(Neighbour.class));
                });
    }

    /**
     * The ticket's own scenario: the nightly checkpoint build against the scratch sweep. Under the
     * auto-configured scheduler this case was already safe — a cron tick is handed off to its own
     * virtual thread — so what this pins is the pool <em>size</em>: with the bean in place both
     * tasks want a pool thread, and a pool of one would reintroduce exactly the reported failure.
     */
    @Test
    @DisplayName("The application scheduler keeps a fixed-delay sweep running while a cron build blocks")
    void shouldRunFixedDelaySweepWhileCronBuildBlocks() {
        runner()
                .withUserConfiguration(SchedulingConfiguration.class, CronHogConfiguration.class)
                .run(context -> assertNeighbourKeepsTicking(
                        context.getBean(Hog.class), context.getBean(Neighbour.class)));
    }

    /**
     * A running task must survive the context closing under it, exactly as it did on the virtual
     * threads this pool replaces. Interrupting it would be worse than letting it die with the
     * process: {@code CheckpointService} catches the resulting exception per table and detaches
     * that table's snapshot key, so an interrupted build leaves a 404 behind it.
     */
    @Test
    @DisplayName("closing the context neither interrupts a running task nor waits on its thread")
    void shouldNotInterruptARunningTaskOnShutdown() throws InterruptedException {
        ShutdownWitness witness = new ShutdownWitness();

        runner()
                .withUserConfiguration(SchedulingConfiguration.class)
                .withBean(ShutdownWitness.class, () -> witness)
                .withUserConfiguration(ShutdownWitnessConfiguration.class)
                .run(context -> witness.awaitStarted());

        assertTrue(witness.awaitFinished(), "the task never finished after the context closed");
        assertFalse(witness.wasInterrupted(),
                "shutdown interrupted a running scheduled task. Boot's await-termination default is "
                        + "false, which means shutdownNow(); SchedulingConfiguration overrides it because "
                        + "an interrupted checkpoint build detaches the table it was writing");
        assertTrue(witness.ranOnDaemonThread(),
                "without the interrupt, a non-daemon pool thread would hold the JVM open after the "
                        + "context closed until the pod's grace period expired");
    }

    private static void assertNeighbourKeepsTicking(Hog hog, Neighbour neighbour) throws InterruptedException {
        try {
            hog.awaitStarted();
            int base = neighbour.ticks();

            Awaitility.await("neighbour ticks while the hog holds a scheduler thread")
                    .atMost(PROGRESS_WINDOW)
                    .pollInterval(Duration.ofMillis(TICK_MS))
                    .until(() -> neighbour.ticks() >= base + TICKS_PROVING_PROGRESS);

            assertTrue(hog.stillBlocked(), "the hog must still be holding its thread for the run to prove anything");
        } finally {
            hog.release();
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues(
                        // Both as shipped: virtual threads are on application-wide, and the pool size
                        // is the one an operator would find in application.yml.
                        "spring.threads.virtual.enabled=true",
                        "spring.task.scheduling.pool.size=" + shippedPoolSize());
    }

    /** The pool size {@code application.yml} declares, so the shipped value is what gets exercised. */
    static int shippedPoolSize() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));
            assertEquals(1, sources.size(), "application.yml should yield a single document");
            Object value = sources.get(0).getProperty("spring.task.scheduling.pool.size");
            assertNotNull(value, "spring.task.scheduling.pool.size must be declared (issue #146)");
            return Integer.parseInt(value.toString().trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A scheduled task that occupies its thread until released. */
    static class Hog {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        void hog() throws InterruptedException {
            started.countDown();
            release.await(HOG_BUDGET_SECONDS, TimeUnit.SECONDS);
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(PROGRESS_WINDOW.toSeconds(), TimeUnit.SECONDS), "the hog never started");
        }

        boolean stillBlocked() {
            return release.getCount() > 0;
        }

        void release() {
            release.countDown();
        }
    }

    /** A cheap tick standing in for the scratch sweep, the batch timeout sweep, the partition job. */
    static class Neighbour {

        private final AtomicInteger ticks = new AtomicInteger();

        void tick() {
            ticks.incrementAndGet();
        }

        int ticks() {
            return ticks.get();
        }
    }

    static class FixedDelayHog extends Hog {

        @Scheduled(fixedDelay = TICK_MS)
        void run() throws InterruptedException {
            hog();
        }
    }

    static class CronHog extends Hog {

        @Scheduled(cron = "* * * * * *")
        void run() throws InterruptedException {
            hog();
        }
    }

    static class FixedDelayNeighbour extends Neighbour {

        @Scheduled(fixedDelay = TICK_MS)
        void run() {
            tick();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class FixedDelayHogConfiguration {

        @Bean
        Hog hog() {
            return new FixedDelayHog();
        }

        @Bean
        Neighbour neighbour() {
            return new FixedDelayNeighbour();
        }
    }

    /** Records how a task fared while the context was being closed around it. */
    static class ShutdownWitness {

        /** Long enough that the context is closing while the task is still inside its sleep. */
        private static final long WORK_MS = 1_500L;

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean interrupted;
        private volatile boolean daemon;

        @Scheduled(fixedDelay = TICK_MS)
        void work() {
            if (started.getCount() == 0) {
                return;
            }
            daemon = Thread.currentThread().isDaemon();
            started.countDown();
            try {
                Thread.sleep(WORK_MS);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(PROGRESS_WINDOW.toSeconds(), TimeUnit.SECONDS), "the task never started");
        }

        boolean awaitFinished() throws InterruptedException {
            return finished.await(PROGRESS_WINDOW.toSeconds(), TimeUnit.SECONDS);
        }

        boolean wasInterrupted() {
            return interrupted;
        }

        boolean ranOnDaemonThread() {
            return daemon;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ShutdownWitnessConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class CronHogConfiguration {

        @Bean
        Hog hog() {
            return new CronHog();
        }

        @Bean
        Neighbour neighbour() {
            return new FixedDelayNeighbour();
        }
    }
}
