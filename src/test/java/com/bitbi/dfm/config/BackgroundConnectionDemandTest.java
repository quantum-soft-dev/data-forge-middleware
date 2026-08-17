package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ReflectionUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #161 — the audit behind {@code spring.datasource.hikari.maximum-pool-size}.
 *
 * <p>{@code ScheduledTaskInventoryTest} (#146) asserts the narrower property: the scheduler alone
 * cannot empty the connection pool. This class is the wider one that ticket asked for — the pool
 * against <em>every</em> background thread that can hold a connection, per replica and per
 * cluster.</p>
 *
 * <h2>Why the pool is not the sum of the thread ceilings</h2>
 *
 * <p>The audited background pools declare {@value #AUDITED_BACKGROUND_THREADS} threads between
 * them, several times the connection pool, and that is deliberate rather than an oversight. Two
 * facts make it safe:</p>
 *
 * <ol>
 *   <li><b>Background work does not hold one connection while waiting for a second</b>, which is
 *       the shape that turns a shortage into a deadlock rather than a delay. The
 *       {@code REQUIRES_NEW} paths that could nest are arranged not to: {@code CheckpointEpochGuard}
 *       is called from a deliberately non-transactional build, and {@code CheckpointService}
 *       publishes {@code CheckpointRecordedEvent} outside the guard's transaction on purpose (there,
 *       to avoid a self-deadlock on the {@code site_sync_state} row lock).
 *       <p><b>No exception remains.</b> The two this class used to name are both closed:
 *       {@code DeltaSqlQueueService.processNextPending} pinning a connection across the generation
 *       semaphore was removed by <b>#164</b>, and {@code PluginAuditEventListener} — an
 *       {@code AFTER_COMMIT} listener whose own write is {@code REQUIRES_NEW}, handed back to the
 *       publishing thread by {@code pluginExecutor}'s {@code CallerRunsPolicy} once that pool
 *       saturated — by <b>#171</b>: the write now goes to {@code pluginAuditExecutor}, whose
 *       rejection policy drops and logs instead of running inline, and the listener refuses to write
 *       at all on a thread that already has a transaction open. <b>Adding one back is a change to
 *       this reasoning, not just to a pool.</b></p>
 *   </li>
 *   <li><b>Waiting is a survivable outcome for background work.</b> The failure is a 30 s
 *       {@code connection-timeout} on a queue worker or a scheduled tick, both of which run again
 *       on their next wake. It is not a survivable outcome for a consumer that has already pinned
 *       a connection across an S3 round trip, which is why the pool has to cover the
 *       {@link Hold#LONG} consumers outright and may leave the short ones to queue.</li>
 * </ol>
 *
 * <p>So the sizing rule is a floor over the long holders plus a reserve
 * ({@link #thePoolCoversEveryLongHoldingBackgroundConsumer}) and a ceiling from the cluster's
 * connection budget ({@link #theClusterConnectionBudgetHolds}). The derivation is written out
 * beside the key in {@code application.yml}; this class is what stops it going stale.</p>
 *
 * <p><b>What makes it go stale</b> is a new background pool, which is why the inventory below is
 * discovered three ways rather than hand-listed: every {@code @Bean} returning an {@link Executor},
 * every {@code max-concurrent} property, and every pool constructed directly in production source.
 * A newcomer fails this class until someone decides which {@link Hold} it has.</p>
 */
@DisplayName("Background connection demand (#161)")
class BackgroundConnectionDemandTest {

    private static final String POOL_KEY = "spring.task.scheduling.pool.size";

    private static final String HIKARI_KEY = "spring.datasource.hikari.maximum-pool-size";

    /** How long a consumer keeps a connection once it has one. */
    private enum Hold {

        /**
         * Pins a connection across S3 round trips or a bounded wait — seconds to minutes. These are
         * the consumers the pool has to cover outright: they do not release between statements, so
         * a shortage stalls them rather than slowing them.
         */
        LONG,

        /**
         * Statements only; the connection is back in the pool in milliseconds. Allowed to queue.
         */
        SHORT,

        /** Never opens a connection, so it is not demand at all. */
        NONE
    }

    /**
     * One bounded source of background threads, and what it does with a connection.
     *
     * @param threads   the pool's own ceiling, not how many are usually busy; {@code 0} where the
     *                  size comes from a configuration key and is read from the YAML instead
     * @param hold      how long one of its threads keeps a connection
     * @param rationale why it has that hold, in the terms an operator would check
     */
    private record Consumer(int threads, Hold hold, String rationale) {
    }

    // ---------------------------------------------------------------------------------------
    // The inventory
    // ---------------------------------------------------------------------------------------

    /** Bean-declared executors, keyed by the {@code @Bean} method that declares them. */
    private static final Map<String, Consumer> EXECUTOR_BEANS = executorBeans();

    private static Map<String, Consumer> executorBeans() {
        Map<String, Consumer> beans = new LinkedHashMap<>();
        // Every @Scheduled method plus BatchRetentionScheduler's programmatic cron. Its whole
        // ceiling is background demand, but only the tasks ScheduledTaskInventoryTest classifies
        // Cost.LONG are demand the pool has to *cover* — see longHoldingThreads(), which counts
        // them rather than the six threads. A short tick that waits for a connection simply runs
        // again on its next wake, and conflating "holds a thread" with "holds a connection" here
        // would be the same mistake this class exists to correct elsewhere.
        beans.put("com.bitbi.dfm.config.SchedulingConfiguration#taskScheduler",
                new Consumer(6, Hold.SHORT, "sized by " + POOL_KEY + "; its long ticks counted"
                        + " separately from ScheduledTaskInventoryTest"));
        // Immediate plugin audit writes and the async SQL-generation entry point: one INSERT each.
        // The methods on PluginAuditService are @Transactional (REQUIRED), so a CallerRunsPolicy
        // overflow joins the caller's transaction rather than asking for a second connection.
        beans.put("com.bitbi.dfm.plugin.infrastructure.PluginAsyncConfiguration#pluginExecutor",
                new Consumer(10, Hold.SHORT, "one audit INSERT per task"));
        // The deferred audit writes of PluginAuditEventListener, given a lane of their own by #171:
        // one INSERT each, and a rejection policy that drops rather than handing the write back to
        // the publishing thread, which is inside afterCommit still holding its own connection.
        beans.put("com.bitbi.dfm.plugin.infrastructure.PluginAsyncConfiguration#pluginAuditExecutor",
                new Consumer(2, Hold.SHORT, "one audit INSERT per task; never runs inline (#171)"));
        // Plugin dispatch; the SQL generation it fans out to is gated by the semaphore below.
        beans.put("com.bitbi.dfm.plugin.infrastructure.PluginAsyncConfiguration#pluginExecutionExecutor",
                new Consumer(8, Hold.SHORT, "dispatch, then the generation's own transaction"));
        // A forced checkpoint rebuild. CheckpointService.buildCheckpoint is non-transactional by
        // design (#136): its writes are short guarded transactions between S3 round trips.
        beans.put("com.bitbi.dfm.config.AsyncConfiguration#deltaRebuildExecutor",
                new Consumer(1, Hold.SHORT, "checkpoint build takes short guarded transactions"));
        // `comparisonExecutor` used to sit here as Hold.NONE — declared, initialized and reachable
        // by nothing. It was deleted by #165; this inventory is what fails if it, or anything else
        // with no caller, is declared again.
        return beans;
    }

    /**
     * Pools whose size is a configuration key. The size is read from the YAML, so lowering a
     * worker's concurrency relaxes the floor below without anyone having to remember this file.
     */
    private static final Map<String, Consumer> CONFIGURED_POOLS = configuredPools();

    private static Map<String, Consumer> configuredPools() {
        Map<String, Consumer> pools = new LinkedHashMap<>();
        // DeltaEgressService.egressNextPending opens no transaction of its own (#164): the claim
        // and the egress_at write are short repository transactions; S3 runs with nothing open.
        pools.put("delta.egress.max-concurrent",
                new Consumer(0, Hold.SHORT, "claim/mark only; S3 runs with no transaction (#164)"));
        // DeltaSqlQueueService.processNextPending acquires the generation semaphore before any
        // transaction opens, and generation's S3 I/O is likewise outside a transaction (#164).
        pools.put("plugin.sql-generation.delta-max-concurrent",
                new Consumer(0, Hold.SHORT, "semaphore then S3, both outside a transaction (#164)"));
        // The build itself runs with no transaction open; every database step is its own short
        // REQUIRES_NEW template (settle, claim, publish).
        pools.put("delta.batch-parquet.max-concurrent",
                new Consumer(0, Hold.SHORT, "claim/publish only; the build holds no connection"));
        // A semaphore, not a pool: it admits generation work that has not yet opened a transaction
        // (#164), so it adds no demand of its own.
        pools.put("plugin.sql-generation.max-concurrent",
                new Consumer(0, Hold.NONE, "semaphore acquired before any transaction (#164)"));
        // Not a thread pool at all — a per-account business rule checked inside a request.
        pools.put("account.max-concurrent-batches",
                new Consumer(0, Hold.NONE, "business rule, not a pool"));
        return pools;
    }

    /**
     * Pools built directly in production code rather than declared as beans, keyed by source file
     * and counted, so a second pool added to a file that already has one is still a newcomer.
     *
     * <p>{@code SchedulingConfiguration} is deliberately absent: its scheduler is a subclass
     * instantiated by name, which no pattern here would recognise, and the {@code @Bean} scan
     * already holds it.</p>
     */
    private static final Map<String, Integer> POOL_CONSTRUCTIONS = poolConstructions();

    private static Map<String, Integer> poolConstructions() {
        Map<String, Integer> constructions = new TreeMap<>();
        constructions.put("com/bitbi/dfm/config/AsyncConfiguration.java", 1);
        constructions.put("com/bitbi/dfm/plugin/infrastructure/PluginAsyncConfiguration.java", 3);
        constructions.put("com/bitbi/dfm/delta/application/DeltaEgressWorker.java", 1);
        constructions.put("com/bitbi/dfm/delta/application/BatchParquetFinalizationWorker.java", 1);
        constructions.put("com/bitbi/dfm/plugin/application/DeltaSqlSweepWorker.java", 1);
        constructions.put("com/bitbi/dfm/delta/application/BatchParquetFinalizationService.java", 1);
        // Not a pool: one CompletableFuture.runAsync that names pluginExecutionExecutor explicitly,
        // which is the form that keeps work off ForkJoinPool.commonPool. Recorded so a second
        // hand-off has to justify its executor rather than inherit the common pool silently.
        constructions.put("com/bitbi/dfm/plugin/application/PluginEventDispatcher.java", 1);
        return constructions;
    }

    /**
     * The lease-renewal thread of {@code BatchParquetFinalizationService} — one thread, outside
     * both maps above because it is neither a bean nor sized by a key. It re-locks the batch every
     * third of the lease while a build is in flight, so it is held concurrently with the finalizer
     * threads rather than instead of them.
     */
    private static final Consumer BATCH_PARQUET_LEASE =
            new Consumer(1, Hold.SHORT, "one advisory-locked UPDATE every lease-seconds/3");

    /**
     * Slots the modelled background peak is required to leave for request threads.
     *
     * <p><b>Nothing enforces this at runtime, and it is not meant to.</b> HikariCP has no notion of
     * a reserved partition, so the {@link Hold#SHORT} background threads may certainly
     * occupy these two for a moment — that is acceptable exactly because their holds are
     * milliseconds, which is the same reason they are not in the floor. What the reserve buys is
     * that the consumers which <em>cannot</em> give a slot back quickly are never budgeted to fill
     * the pool completely, so a request arriving during a background peak queues behind
     * milliseconds rather than behind an S3 round trip.</p>
     *
     * <p>It is a modelling allowance rather than a derived quantity: the request layer is unbounded
     * on both sides — Tomcat runs virtual-thread-per-request
     * ({@code spring.threads.virtual.enabled}) and the gRPC server takes grpc-java's default cached
     * pool — so no reserve can be <em>sized</em> for it without the
     * {@code hikari_connections_pending} observation issue #161 asks for. (It is deliberately not
     * justified by the health probes: {@code /actuator/health/liveness} and {@code readiness} carry
     * only the state indicators and never open a connection.)</p>
     */
    private static final int REQUEST_RESERVE = 2;

    /**
     * What a PostgreSQL server left at its shipped defaults will accept.
     *
     * <p>An assumption, deliberately the pessimistic one: this repository does not declare the
     * database — there is no {@code DB_URL} in {@code k8s/}, it arrives from the secret — so the
     * only number that can be reasoned about here is the one an unconfigured server has. It is also
     * <b>optimistic about topology</b>, in the one way worth naming: it assumes this cluster is the
     * only thing on that server. Dev and stage each reach {@code (3 + 1) x pool} of their own, so
     * if two environments share a database the real budget is the sum, and this constant is wrong
     * by a factor of two.</p>
     *
     * <p>Going past this budget is safe only after reading {@code SHOW max_connections} on the
     * actual server, and that check has to come first, because the cost of getting it wrong is
     * {@code FATAL: sorry, too many clients already} on every replica rather than a slow query.</p>
     */
    private static final int DEFAULT_MAX_CONNECTIONS = 100;

    /**
     * Connections this application must not claim: PostgreSQL's own
     * {@code superuser_reserved_connections} (3 by default) plus the sessions an operator, a
     * migration run or a monitoring exporter still needs while the cluster sits at its peak.
     */
    private static final int OPERATOR_RESERVE = 10;

    /**
     * Threads that can hold a connection, across every audited background pool. Quoted in the class
     * documentation above and in the derivation beside the key; recomputed by
     * {@link #theAuditedTotalIsWhatItSays}.
     */
    static final int AUDITED_BACKGROUND_THREADS = 34;

    // ---------------------------------------------------------------------------------------
    // The two bounds
    // ---------------------------------------------------------------------------------------

    /**
     * Background threads that can hold a connection across S3 I/O or a bounded wait.
     *
     * <p>The scheduler contributes the tasks {@code ScheduledTaskInventoryTest} classifies as long,
     * not its six threads: a short tick that has to wait for a connection runs again on its next
     * wake, so it belongs with the queueing consumers however many threads exist to dispatch it.
     * Adding a {@code Cost.LONG} scheduled task therefore tightens this floor automatically.</p>
     *
     * <p><b>That borrowed count is a deliberate over-estimate, and worth naming as one</b>, because
     * this class otherwise insists on the distinction: {@code Cost} measures how long a task holds
     * its <em>thread</em>, and one of the four long ticks — {@code CheckpointScheduler} — holds no
     * connection for any of that time, since the build is non-transactional and writes through
     * short guarded transactions (which is exactly why the same work is {@link Hold#SHORT} when it
     * arrives through {@code deltaRebuildExecutor}). The connection-true count is three. Four is
     * used anyway: the two classifications are one per-task audit rather than two that could
     * disagree, and erring upward makes the floor stricter than reality rather than looser. Both
     * values leave the shipped pool satisfying it.</p>
     */
    private static int longHoldingThreads() {
        return ScheduledTaskInventoryTest.longRunningTaskCount() + totalThreads(Hold.LONG);
    }

    @Test
    @DisplayName("the pool covers every long-holding background consumer and still leaves room for requests")
    void thePoolCoversEveryLongHoldingBackgroundConsumer() {
        int longHolders = longHoldingThreads();
        int poolSize = hikariPoolSize();

        assertTrue(longHolders + REQUEST_RESERVE <= poolSize,
                HIKARI_KEY + " is " + poolSize + ", but " + longHolders + " background threads can "
                        + "each pin a connection across S3 I/O or a bounded wait, leaving "
                        + (poolSize - longHolders) + " for request threads instead of the "
                        + REQUEST_RESERVE + " this derivation reserves. Either raise the pool — within "
                        + "the cluster budget theClusterConnectionBudgetHolds asserts — or lower one "
                        + "of the LONG consumers in this inventory. Shortening a hold so it stops "
                        + "being LONG counts too, and is the better fix");
    }

    @Test
    @DisplayName("the whole cluster at its replica ceiling still fits a default PostgreSQL")
    void theClusterConnectionBudgetHolds() throws IOException {
        int replicas = maxReplicas();
        int surge = maxSurge();
        int poolSize = hikariPoolSize();
        int wanted = (replicas + surge) * poolSize;

        assertTrue(wanted + OPERATOR_RESERVE <= DEFAULT_MAX_CONNECTIONS,
                "at the replica ceiling this deployment declares (" + replicas + " from the k8s HPAs, "
                        + "plus " + surge + " surging during a rollout) a pool of " + poolSize
                        + " wants " + wanted + " connections, which leaves less than the "
                        + OPERATOR_RESERVE + " this derivation keeps free of PostgreSQL's default "
                        + "max_connections=" + DEFAULT_MAX_CONNECTIONS + ". Raising either the pool or "
                        + "maxReplicas past this needs the real `SHOW max_connections` of the server "
                        + "behind DB_URL, recorded beside the key it justifies");
    }

    // ---------------------------------------------------------------------------------------
    // The inventory cannot go stale
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("every @Bean executor in the application is one the pool size was audited against")
    void shouldFindExactlyTheAuditedExecutorBeans() {
        Set<String> discovered = scanExecutorBeans();

        assertFalse(discovered.isEmpty(),
                "no @Bean executor was found at all — the scan is broken, not the application");
        assertEquals(new TreeSet<>(EXECUTOR_BEANS.keySet()), discovered,
                "the set of executor beans changed. The connection pool is smaller than the threads "
                        + "that can ask it for a connection (issue #161), so a new pool needs the same "
                        + "two judgements a new scheduled task needs: how long does one of its threads "
                        + "hold a connection, and does the floor beside " + HIKARI_KEY
                        + " in application.yml still hold?");
    }

    @Test
    @DisplayName("every executor bean still declares the thread ceiling it was audited with")
    void shouldKeepTheAuditedThreadCeilings() {
        EXECUTOR_BEANS.forEach((bean, audited) -> {
            Integer declared = declaredMaxPoolSize(bean);
            if (declared == null) {
                // A @Bean method with arguments (the TaskScheduler takes Boot's builder) is sized
                // by a property instead, and that side is asserted from the YAML below.
                return;
            }
            assertEquals(audited.threads(), declared.intValue(),
                    bean + " now declares " + declared + " threads, not the " + audited.threads()
                            + " this audit counted. Redo the arithmetic beside " + HIKARI_KEY
                            + " in application.yml before changing the number here");
        });
    }

    @Test
    @DisplayName("the scheduler pool is counted at the size the YAML actually declares")
    void shouldCountTheSchedulerAtItsConfiguredSize() {
        Consumer scheduler =
                EXECUTOR_BEANS.get("com.bitbi.dfm.config.SchedulingConfiguration#taskScheduler");

        assertEquals(schedulerPoolSize(), scheduler.threads(),
                POOL_KEY + " moved. It is the largest single block of background threads, so the "
                        + "floor beside " + HIKARI_KEY + " moves with it");
    }

    @Test
    @DisplayName("every max-concurrent property is one this audit has classified")
    void shouldFindExactlyTheAuditedConcurrencyKeys() {
        Set<String> discovered = new TreeSet<>();
        applicationProperties().keySet().stream()
                .filter(key -> key.contains("max-concurrent"))
                .forEach(discovered::add);

        assertFalse(discovered.isEmpty(), "no max-concurrent property was found — the scan is broken");
        assertEquals(new TreeSet<>(CONFIGURED_POOLS.keySet()), discovered,
                "a max-concurrent property was added or renamed. Say whether it sizes a pool of "
                        + "threads that hold connections, and for how long, before the floor beside "
                        + HIKARI_KEY + " in application.yml can be trusted again");
    }

    @Test
    @DisplayName("no pool is constructed in production code outside this inventory")
    void shouldFindExactlyTheAuditedPoolConstructions() throws IOException {
        Map<String, Integer> discovered = scanPoolConstructions();

        assertEquals(POOL_CONSTRUCTIONS, discovered,
                "a thread pool is constructed somewhere this audit does not know about. Not every "
                        + "pool is a @Bean — DeltaEgressWorker, BatchParquetFinalizationWorker, "
                        + "DeltaSqlSweepWorker and the batch-parquet lease renewer all build their "
                        + "own — so this scan is the backstop for the bean scan above");
    }

    @Test
    @DisplayName("the total quoted in the derivation is the total this inventory adds up to")
    void theAuditedTotalIsWhatItSays() {
        int total = totalThreads(Hold.LONG) + totalThreads(Hold.SHORT);

        assertEquals(AUDITED_BACKGROUND_THREADS, total,
                "the audited background thread ceiling changed. It is quoted in this class's "
                        + "documentation and in the derivation beside " + HIKARI_KEY
                        + " in application.yml — both say it deliberately exceeds the pool, and both "
                        + "have to keep saying a true number");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Threads of one hold class, taking a configured pool's size from the YAML. */
    private static int totalThreads(Hold hold) {
        int total = 0;
        for (Consumer consumer : EXECUTOR_BEANS.values()) {
            if (consumer.hold() == hold) {
                total += consumer.threads();
            }
        }
        for (Map.Entry<String, Consumer> entry : CONFIGURED_POOLS.entrySet()) {
            if (entry.getValue().hold() == hold) {
                total += requiredInt(entry.getKey());
            }
        }
        if (BATCH_PARQUET_LEASE.hold() == hold) {
            total += BATCH_PARQUET_LEASE.threads();
        }
        return total;
    }

    private static int hikariPoolSize() {
        return requiredInt(HIKARI_KEY);
    }

    private static int schedulerPoolSize() {
        return requiredInt(POOL_KEY);
    }

    private static int requiredInt(String key) {
        Object value = applicationProperties().get(key);
        assertNotNull(value, key + " must be declared in application.yml");
        return ScheduledTaskInventoryTest.parseInt(key, value);
    }

    private static Map<String, Object> applicationProperties() {
        Map<String, Object> properties = ScheduledTaskInventoryTest.optionalYaml("application.yml");
        assertNotNull(properties, "application.yml must be on the classpath");
        return properties;
    }

    /**
     * Highest {@code maxReplicas} the backend's HPAs declare, across the base and every overlay.
     *
     * <p>Selection is by content rather than by filename: a document is only counted when it is a
     * {@code HorizontalPodAutoscaler} whose {@code scaleTargetRef} names the backend deployment. A
     * name-based filter would both miss a renamed patch and, worse, count the frontend's HPA as
     * this application's replica ceiling.</p>
     */
    private static int maxReplicas() throws IOException {
        int highest = 0;
        for (Path manifest : manifests()) {
            Integer declared = backendHpaMaxReplicas(manifest);
            if (declared != null) {
                highest = Math.max(highest, declared);
            }
        }
        assertTrue(highest > 0,
                "no HorizontalPodAutoscaler targeting " + BACKEND_DEPLOYMENT + " declares maxReplicas; "
                        + "without a replica ceiling the cluster connection budget cannot be computed");
        return highest;
    }

    private static final String BACKEND_DEPLOYMENT = "forge-backend";

    /** {@code spec.maxReplicas} when this document is the backend's HPA, otherwise {@code null}. */
    private static Integer backendHpaMaxReplicas(Path manifest) throws IOException {
        for (Object document : documents(manifest)) {
            if (!(document instanceof Map<?, ?> root)) {
                continue;
            }
            if (!"HorizontalPodAutoscaler".equals(root.get("kind"))) {
                continue;
            }
            if (!(root.get("spec") instanceof Map<?, ?> spec)) {
                continue;
            }
            // An overlay patch carries only the fields it changes, so a target-less patch is still
            // the backend's as long as its metadata.name matches the base HPA's.
            boolean targetsBackend = spec.get("scaleTargetRef") instanceof Map<?, ?> target
                    ? BACKEND_DEPLOYMENT.equals(target.get("name"))
                    : root.get("metadata") instanceof Map<?, ?> metadata
                            && String.valueOf(metadata.get("name")).startsWith(BACKEND_DEPLOYMENT);
            if (targetsBackend && spec.get("maxReplicas") instanceof Number maxReplicas) {
                return maxReplicas.intValue();
            }
        }
        return null;
    }

    /**
     * Extra pods a rolling update may add on top of the replica ceiling, across the base and every
     * overlay — an overlay that raises the surge raises the real peak just as an overlay that
     * raises {@code maxReplicas} does.
     *
     * <p>Selected from the <em>backend</em> Deployment specifically, the way {@link #maxReplicas()}
     * selects its HPA. The frontend's Deployment declares a surge of its own and adds no pod that
     * opens a connection, so folding it in would fail this budget for a rollout that cannot affect
     * it.</p>
     */
    private static int maxSurge() throws IOException {
        int highest = -1;
        for (Path manifest : manifests()) {
            for (Object document : documents(manifest)) {
                if (!(document instanceof Map<?, ?> root) || !"Deployment".equals(root.get("kind"))) {
                    continue;
                }
                if (!(root.get("metadata") instanceof Map<?, ?> metadata)
                        || !BACKEND_DEPLOYMENT.equals(metadata.get("name"))) {
                    continue;
                }
                Object declared = root.get("spec") instanceof Map<?, ?> spec
                        && spec.get("strategy") instanceof Map<?, ?> strategy
                        && strategy.get("rollingUpdate") instanceof Map<?, ?> rolling
                        ? rolling.get("maxSurge") : null;
                if (declared == null) {
                    continue;
                }
                assertTrue(declared instanceof Number,
                        manifest + " declares maxSurge: " + declared + ". Only a plain integer can "
                                + "be budgeted for — a percentage makes the peak pod count depend on "
                                + "the current replica count");
                highest = Math.max(highest, ((Number) declared).intValue());
            }
        }
        assertTrue(highest >= 0,
                "no manifest declares rollingUpdate.maxSurge for " + BACKEND_DEPLOYMENT + ", so the "
                        + "pod count during a rollout is Kubernetes' default of 25% rather than "
                        + "something this budget can compute");
        return highest;
    }

    /**
     * Every key of the derivation, in the relaxed-binding form a manifest would use.
     *
     * <p>The reach of this whole class is {@code application.yml}, and every number in the
     * derivation is overridable from the environment. A ConfigMap that sets one of these would move
     * the real arithmetic while both bounds above stayed green on the defaults, which is the same
     * hole {@code ParquetScratchCeilingBudgetTest} closes for the scratch ceilings — and the guide's
     * own advice ("lower {@code DELTA_EGRESS_MAX_CONCURRENT}") would otherwise be advice to do
     * exactly that.</p>
     *
     * <p>It bounds the manifests, not the world: an override reaching the pod some other way
     * ({@code SPRING_APPLICATION_JSON}, a Secret, a `kubectl set env`) is outside anything this
     * repository can check, and is why the derivation asks for the change to be made in the open.</p>
     */
    private static final List<String> DERIVATION_ENV_KEYS = List.of(
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE",
            "SPRING_TASK_SCHEDULING_POOL_SIZE",
            "DELTA_EGRESS_MAX_CONCURRENT",
            "PLUGIN_SQL_GENERATION_DELTA_MAX_CONCURRENT");

    @Test
    @DisplayName("no manifest overrides a key the derivation is computed from")
    void shouldNotLetAManifestMoveTheDerivationBehindTheTest() throws IOException {
        for (Path manifest : manifests()) {
            String text = Files.readString(manifest);
            for (String key : DERIVATION_ENV_KEYS) {
                assertFalse(text.contains(key),
                        manifest + " sets " + key + ". Every bound in this class is computed from "
                                + "application.yml, so an override here moves the real arithmetic "
                                + "while the assertions stay green on the defaults. Change the "
                                + "default and the derivation beside it instead, or teach this test "
                                + "to read the override");
            }
        }
    }

    private static List<Path> manifests() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("k8s"))) {
            return tree.filter(path -> path.toString().endsWith(".yaml")
                    || path.toString().endsWith(".yml")).toList();
        }
    }

    /** Every document of a manifest, tolerating multi-document files and JSON-6902 patch lists. */
    private static Iterable<Object> documents(Path manifest) throws IOException {
        List<Object> documents = new java.util.ArrayList<>();
        new Yaml().loadAll(Files.readString(manifest)).forEach(documents::add);
        return documents;
    }

    /** Every {@code Class#method} annotated {@code @Bean} whose product is an {@link Executor}. */
    private static Set<String> scanExecutorBeans() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        Set<String> found = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.bitbi.dfm")) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type;
            try {
                // Loaded without initialization: reading annotations must not run static blocks.
                type = Class.forName(className, false,
                        BackgroundConnectionDemandTest.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (!isProductionClass(type)) {
                continue;
            }
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (method.isAnnotationPresent(Bean.class)
                        && Executor.class.isAssignableFrom(method.getReturnType())) {
                    found.add(type.getName() + "#" + method.getName());
                }
            }
        }
        return found;
    }

    /**
     * The {@code maxPoolSize} an executor bean declares, or {@code null} for the <em>one</em> case
     * this check cannot cover: a {@code @Bean} method that takes arguments, and is therefore sized
     * by what the container hands it rather than by anything in its own body.
     *
     * <p>Everything else fails rather than returning {@code null}. A silent skip here would be the
     * worst of both worlds — the test would keep reading like a guard while passing vacuously for
     * exactly the pool kinds most likely to be added next (a {@code @Configuration} with
     * constructor injection, or a factory returning a bare {@code ExecutorService}).</p>
     */
    private static Integer declaredMaxPoolSize(String bean) {
        String className = bean.substring(0, bean.indexOf('#'));
        String methodName = bean.substring(bean.indexOf('#') + 1);
        Class<?> type;
        Method factory;
        try {
            type = Class.forName(className);
            factory = Stream.of(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(bean + " no longer declares that method"));
        } catch (ClassNotFoundException e) {
            throw new AssertionError("could not load " + className, e);
        }
        if (factory.getParameterCount() > 0) {
            return null;
        }
        Object product;
        try {
            product = factory.invoke(type.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(bean + " could not be built without the container. If it now "
                    + "takes constructor injection, its thread ceiling has to be asserted some other "
                    + "way rather than left unchecked", e);
        }
        if (product instanceof ThreadPoolTaskExecutor executor) {
            try {
                return executor.getMaxPoolSize();
            } finally {
                executor.shutdown();
            }
        }
        if (product instanceof ThreadPoolExecutor executor) {
            try {
                return executor.getMaximumPoolSize();
            } finally {
                executor.shutdown();
            }
        }
        throw new AssertionError(bean + " produces a " + product.getClass().getName()
                + ", whose thread ceiling this check cannot read. Teach it that type — an executor "
                + "whose size cannot be asserted is one whose size can drift under the derivation "
                + "beside " + HIKARI_KEY);
    }

    /**
     * Ways production code can put work on a thread that is not a request thread.
     *
     * <p>Deliberately includes the <em>unbounded</em> ones — {@code SimpleAsyncTaskExecutor},
     * {@code ForkJoinPool.commonPool()} and a {@code CompletableFuture} hand-off — even though none
     * is used today. A pool with a ceiling is the easy case for this audit; a hand-off with no
     * ceiling is the one that would quietly break the derivation, so the backstop has to be
     * tightest exactly where the inventory is currently empty. A {@code CompletableFuture} match is
     * not necessarily a finding: {@code PluginEventDispatcher} names {@code pluginExecutionExecutor}
     * explicitly, which is the correct form and is why its count is recorded rather than zero. What
     * fails the build is a <em>new</em> one, which then has to name its executor too.</p>
     */
    private static final Pattern POOL_CONSTRUCTION = Pattern.compile(
            "Executors\\.new|new ThreadPoolExecutor\\(|new ThreadPoolTaskExecutor\\(|"
                    + "new ThreadPoolTaskScheduler\\(|new ScheduledThreadPoolExecutor\\(|"
                    + "new ForkJoinPool\\(|ForkJoinPool\\.commonPool\\(|new SimpleAsyncTaskExecutor\\(|"
                    + "CompletableFuture\\.supplyAsync\\(|CompletableFuture\\.runAsync\\(");

    /** Line and block comments, so prose about a thread pool is not counted as one. */
    private static final Pattern JAVA_COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static Map<String, Integer> scanPoolConstructions() throws IOException {
        Path root = Path.of("src/main/java");
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(root)) {
            List<Path> files = sources.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path file : files) {
                String code = JAVA_COMMENT.matcher(Files.readString(file)).replaceAll("");
                int count = 0;
                Matcher matcher = POOL_CONSTRUCTION.matcher(code);
                while (matcher.find()) {
                    count++;
                }
                if (count > 0) {
                    found.put(root.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        return found;
    }

    /** Where this test's own classes live; everything else on the classpath is production. */
    private static final java.net.URL TEST_OUTPUT_ROOT =
            BackgroundConnectionDemandTest.class.getProtectionDomain().getCodeSource().getLocation();

    private static boolean isProductionClass(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation() != null
                && !source.getLocation().equals(TEST_OUTPUT_ROOT);
    }
}
