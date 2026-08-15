package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #146 — the audit behind {@code spring.task.scheduling.pool.size}, kept from going stale.
 *
 * <p>Widening the scheduler from one thread lets scheduled methods that never overlapped before run
 * at the same time, so the pool size is only defensible against a known list of tasks. This test is
 * that list. It fails when a {@code @Scheduled} method is added, removed or moved, which is the
 * moment to redo two judgements for the newcomer:</p>
 *
 * <ol>
 *   <li><b>Is it safe beside the others?</b> Every task on this list touches its own rows or its own
 *       files, and the ones that could collide already carry their own guard — a
 *       {@code ReentrantLock} in {@code CheckpointScheduler}, an {@code AtomicBoolean} in
 *       {@code BatchRetentionScheduler}, {@code FOR UPDATE SKIP LOCKED} claims with leases in the
 *       three queue workers, and idempotent deletes everywhere else. Two pairings are worth naming.
 *       The checkpoint build and the scratch sweep now really do run together: the sweep only ever
 *       deletes files older than its cutoff, and a live build's scratch is exactly as old as the
 *       build (see {@code ParquetScratchOrphanSweeper}). The two provisional-segment deletes on
 *       {@code DeltaIngestionService} can also overlap: both re-read their rows in one transaction
 *       and both callers swallow the runtime exception a lost race would raise — and being separate
 *       replicas' work in the first place ({@code k8s/base/deployment-backend.yaml} declares two),
 *       nothing here ever depended on one scheduler thread to serialize them.</li>
 *   <li><b>Does it hold its thread?</b> {@link Cost#LONG} tasks are the ones the pool has to be
 *       sized around; see the derivation beside the key in {@code application.yml}.</li>
 * </ol>
 */
@DisplayName("Scheduled task inventory (#146)")
class ScheduledTaskInventoryTest {

    /** How long a task occupies the scheduler thread it is dispatched on. */
    private enum Cost {

        /** Minutes to hours: S3 round trips per site, per segment or per table. */
        LONG,

        /** One database statement, one directory listing, one HTTP call. */
        SHORT,

        /** Submits to the task's own bounded executor and returns; never runs the work inline. */
        HANDOFF
    }

    private static final String BASE_PACKAGE = "com.bitbi.dfm";

    /** Where Gradle puts the production classes; keeps test fixtures out of the scan. */
    private static final String MAIN_OUTPUT = "classes/java/main";

    /**
     * The task that is scheduled programmatically rather than by annotation:
     * {@code BatchRetentionScheduler} takes the {@code TaskScheduler} and installs a cron trigger it
     * can re-arm when an admin edits the schedule. It shares the pool with the annotated ones and so
     * belongs to the audit, but no annotation scan will ever find it.
     */
    private static final Class<?> PROGRAMMATIC_TASK =
            com.bitbi.dfm.batch.application.BatchRetentionScheduler.class;

    private static final Cost PROGRAMMATIC_TASK_COST = Cost.LONG;

    private static final Map<String, Cost> ANNOTATED_TASKS = annotatedTasks();

    private static Map<String, Cost> annotatedTasks() {
        Map<String, Cost> tasks = new LinkedHashMap<>();
        // Walks every site with work, and one site is a frame download, a download per segment and
        // a Parquet write plus upload per table.
        tasks.put("com.bitbi.dfm.delta.application.CheckpointScheduler#buildCheckpoints", Cost.LONG);
        // Fallback wakes for the three queue workers: each submits a drain to its own pool.
        tasks.put("com.bitbi.dfm.delta.application.BatchParquetFinalizationWorker#sweep", Cost.HANDOFF);
        tasks.put("com.bitbi.dfm.delta.application.DeltaEgressWorker#sweep", Cost.HANDOFF);
        tasks.put("com.bitbi.dfm.plugin.application.DeltaSqlSweepWorker#sweep", Cost.HANDOFF);
        // One listing per scratch directory, plus the deletes; the tick whose timeliness the sizing
        // note in docs/delta-client-v2-guide.md depends on.
        tasks.put("com.bitbi.dfm.delta.application.ParquetScratchOrphanSweeper#sweep", Cost.SHORT);
        // One SELECT plus a bounded number of single-row transitions.
        tasks.put("com.bitbi.dfm.batch.application.BatchTimeoutScheduler#checkExpiredBatches", Cost.SHORT);
        // One DDL statement each; a missed run is a failing insert next month.
        tasks.put("com.bitbi.dfm.error.application.PartitionScheduler#createNextMonthPartition", Cost.SHORT);
        tasks.put("com.bitbi.dfm.error.application.PartitionScheduler#dropOldPartitions", Cost.SHORT);
        // One DELETE / UPDATE by cutoff, idempotent.
        tasks.put("com.bitbi.dfm.plugin.application.DownloadLinkPurgeScheduler#purgeStaleLinks", Cost.SHORT);
        tasks.put("com.bitbi.dfm.auth.application.RefreshTokenService#cleanupExpiredTokens", Cost.SHORT);
        tasks.put("com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService#cleanupExpired", Cost.SHORT);
        // One Auth0 token request, serialized against the on-demand refresh by its own lock.
        tasks.put("com.bitbi.dfm.auth.config.Auth0Configuration#refreshTokenScheduled", Cost.SHORT);
        // A scan of the in-memory staged-session map; a batch failure plus a batch-scoped
        // provisional collection per evicted session, which is normally none. Removal from the map
        // is a compare-and-remove, so only one evictor ever acts on a session.
        tasks.put("com.bitbi.dfm.delta.presentation.DeltaIngestionService#evictStaleStagedSessions", Cost.SHORT);
        // Bounded by delta.ingestion.provisional-sweep-batch (500) rows and their post-commit S3
        // deletes, so its worst case is long even though it is empty on a healthy pod.
        tasks.put("com.bitbi.dfm.delta.presentation.DeltaIngestionService#sweepOrphanedProvisionalSegments",
                Cost.LONG);
        return tasks;
    }

    @Test
    @DisplayName("every @Scheduled method in the application is one the pool size was audited against")
    void shouldFindExactlyTheAuditedScheduledMethods() {
        Set<String> discovered = scanScheduledMethods();

        assertFalse(discovered.isEmpty(),
                "no @Scheduled method was found at all — the scan is broken, not the application");
        assertEquals(new TreeSet<>(ANNOTATED_TASKS.keySet()), discovered,
                "the set of scheduled tasks changed. They no longer run one at a time (issue #146), so "
                        + "re-do the audit in this class for the newcomer — can it run beside the others, "
                        + "and does it hold its thread? — then update this inventory and, if it blocks, "
                        + "the pool size in application.yml");
    }

    @Test
    @DisplayName("the programmatically scheduled retention cleanup still shares the same pool")
    void shouldKeepTheProgrammaticTaskInTheAudit() {
        boolean takesTaskScheduler = Arrays.stream(PROGRAMMATIC_TASK.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(TaskScheduler.class::isAssignableFrom);

        assertTrue(takesTaskScheduler,
                PROGRAMMATIC_TASK.getSimpleName() + " is audited as a " + PROGRAMMATIC_TASK_COST
                        + " user of the shared scheduler. If it stopped taking the TaskScheduler, the "
                        + "audit and the pool derivation in application.yml need re-reading");
    }

    @Test
    @DisplayName("the pool leaves a thread free for a short tick while every long task runs")
    void shouldSizeThePoolAboveTheLongRunningTasks() {
        long longRunning = ANNOTATED_TASKS.values().stream().filter(Cost.LONG::equals).count()
                + (PROGRAMMATIC_TASK_COST == Cost.LONG ? 1 : 0);
        int poolSize = ScheduledTaskIsolationTest.shippedPoolSize();

        assertTrue(poolSize > longRunning,
                "spring.task.scheduling.pool.size is " + poolSize + " but " + longRunning
                        + " scheduled tasks can hold a thread for minutes or longer; a short tick would "
                        + "queue behind them, which is the failure of issue #146 in a smaller pool");
    }

    @Test
    @DisplayName("the pool stays below the database pool so scheduled work cannot starve requests")
    void shouldKeepThePoolBelowTheDatabasePool() {
        int poolSize = ScheduledTaskIsolationTest.shippedPoolSize();
        int hikariPoolSize = hikariMaximumPoolSize();

        assertTrue(poolSize < hikariPoolSize,
                "spring.task.scheduling.pool.size (" + poolSize + ") must stay below "
                        + "spring.datasource.hikari.maximum-pool-size (" + hikariPoolSize + "): almost every "
                        + "scheduled task opens a connection, and a burst of ticks must not be able to take "
                        + "them all from the request threads");
    }

    private static int hikariMaximumPoolSize() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            java.util.List<PropertySource<?>> sources =
                    loader.load("application.yml", new ClassPathResource("application.yml"));
            Object value = sources.get(0).getProperty("spring.datasource.hikari.maximum-pool-size");
            assertNotNull(value, "spring.datasource.hikari.maximum-pool-size must be declared");
            return Integer.parseInt(value.toString().trim());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Every {@code Class#method} carrying {@link Scheduled} in the production classes.
     *
     * @return fully qualified {@code class#method} names, sorted
     */
    private static Set<String> scanScheduledMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        Set<String> found = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                // Loaded without initialization: reading annotations must not run static blocks.
                type = Class.forName(className, false, ScheduledTaskInventoryTest.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (!isProductionClass(type)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    found.add(type.getName() + "#" + method.getName());
                }
            }
        }
        return found;
    }

    /** Keeps this test's own scheduled fixtures, which live in the same package, out of the scan. */
    private static boolean isProductionClass(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation() != null
                && source.getLocation().getPath().contains(MAIN_OUTPUT);
    }
}
