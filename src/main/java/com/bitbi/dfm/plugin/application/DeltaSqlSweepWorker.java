package com.bitbi.dfm.plugin.application;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded worker pool draining the delta-SQL work queue (026-bitbi-delta-sql) — the plugin
 * counterpart of {@link com.bitbi.dfm.delta.application.DeltaEgressWorker}.
 *
 * <p>A batch-completed plugin event {@link #wake() wakes} the pool; each drain claims the next
 * pending segment (per-site head, {@code FOR UPDATE SKIP LOCKED}) via
 * {@link DeltaSqlQueueService#processNextPending()} and renders it, looping until the queue is
 * empty. Per-site head-of-line claiming keeps a site's generations in seq order, so
 * {@code /sql-changes} (ordered by {@code created_at}) streams them correctly. A low-frequency
 * sweep re-wakes the pool for segments left pending by a crash or a failed generation.</p>
 *
 * <p>A failed generation no longer stalls the queue (issue #243): the queue service defers that
 * one segment with a backoff and ends the drain, and because the segment is then inside its
 * cooldown the <em>next</em> wake claims a different site's head and drains it to the end. The
 * drain stops rather than continuing so that a systemic failure cannot spend an attempt on every
 * pending segment of every site in one pass. What also ends a drain, without recording anything,
 * is a condition that would meet the next claim too — the memory-pressure refusal of #181 and the
 * pod shutting down.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaSqlSweepWorker {

    private static final Logger log = LoggerFactory.getLogger(DeltaSqlSweepWorker.class);

    /**
     * Key and default for the worker pool size — the one home for both, used by the
     * {@code @Value} placeholder and the validator message alike (issue #185).
     */
    public static final String DELTA_MAX_CONCURRENT_KEY = "plugin.sql-generation.delta-max-concurrent";
    public static final String DEFAULT_DELTA_MAX_CONCURRENT = "2";

    /**
     * Key and default for the fallback sweep interval — shared by the constructor's
     * {@code @Value} (which only validates) and the {@code @Scheduled} on {@link #sweep()}
     * (which is the interval that actually runs), so the two cannot drift apart (issue #185).
     */
    public static final String DELTA_SWEEP_MS_KEY = "plugin.sql-generation.delta-sweep-ms";
    public static final String DEFAULT_DELTA_SWEEP_MS = "60000";

    private final DeltaSqlQueueService queueService;
    private final ThreadPoolExecutor pool;

    public DeltaSqlSweepWorker(DeltaSqlQueueService queueService,
                               @Value("${" + DELTA_MAX_CONCURRENT_KEY + ":" + DEFAULT_DELTA_MAX_CONCURRENT + "}")
                               int maxConcurrent,
                               @Value("${" + DELTA_SWEEP_MS_KEY + ":" + DEFAULT_DELTA_SWEEP_MS + "}")
                               long sweepMillis) {
        this.queueService = queueService;
        // Out of range fails startup (issue #185, fail fast by owner decision — reasoning in
        // docs/020-sql-generation-optimization.md). Without the first check, 0 crash-looped
        // through ArrayBlockingQueue's message-less IllegalArgumentException — naming neither key
        // nor value; without the second, Spring accepted 0 and busy-looped the fallback sweep on
        // a green rollout. sweepMillis is read here only to be validated: the interval that runs
        // is the one @Scheduled on sweep() resolves from the same key.
        PluginConfigValidation.requireAtLeast(
                DELTA_MAX_CONCURRENT_KEY, maxConcurrent, 1,
                "this pool is what drains the delta-SQL queue, and with no threads every "
                        + "segment would stay pending for ever");
        PluginConfigValidation.requireAtLeast(
                DELTA_SWEEP_MS_KEY, sweepMillis, 1L,
                "a non-positive interval busy-loops the fallback sweep");
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxConcurrent),
                runnable -> {
                    Thread thread = new Thread(runnable, "delta-sql-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Nudge the pool to drain pending segments. Safe to call from anywhere at any rate.
     */
    public void wake() {
        pool.execute(this::drain);
    }

    /**
     * Fallback sweep: picks up segments missed by the event-time wake (instance crash mid-drain,
     * failed generation, or a plugin reinit re-enqueueing a site's segments).
     */
    @Scheduled(fixedDelayString = "${" + DELTA_SWEEP_MS_KEY + ":" + DEFAULT_DELTA_SWEEP_MS + "}")
    public void sweep() {
        wake();
    }

    private void drain() {
        try {
            while (queueService.processNextPending()) {
                // keep draining until the queue is empty
            }
        } catch (RuntimeException e) {
            // What reaches here is systemic rather than one segment's own failure (issue #243) —
            // today the #181 memory-pressure refusal, which the next claim would meet as well.
            log.warn("Delta SQL drain ended early; the segment stays pending for the sweep: {}",
                    e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
