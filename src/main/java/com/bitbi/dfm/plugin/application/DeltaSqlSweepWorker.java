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
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaSqlSweepWorker {

    private static final Logger log = LoggerFactory.getLogger(DeltaSqlSweepWorker.class);

    private final DeltaSqlQueueService queueService;
    private final ThreadPoolExecutor pool;

    public DeltaSqlSweepWorker(DeltaSqlQueueService queueService,
                               @Value("${plugin.sql-generation.delta-max-concurrent:2}") int maxConcurrent,
                               @Value("${plugin.sql-generation.delta-sweep-ms:60000}") long sweepMillis) {
        this.queueService = queueService;
        // Out of range fails startup (issue #185, fail fast by owner decision — reasoning in
        // docs/020-sql-generation-optimization.md). Without the first check, 0 crash-looped
        // through ArrayBlockingQueue's message-less IllegalArgumentException — naming neither key
        // nor value; without the second, Spring accepted 0 and busy-looped the fallback sweep on
        // a green rollout. sweepMillis is read here only to be validated: the interval that runs
        // is the one @Scheduled on sweep() resolves from the same key.
        PluginConfigValidation.requireAtLeast(
                "plugin.sql-generation.delta-max-concurrent", maxConcurrent, 1,
                "this pool is what drains the delta-SQL queue, and with no threads every "
                        + "segment would stay pending for ever");
        PluginConfigValidation.requireAtLeast(
                "plugin.sql-generation.delta-sweep-ms", sweepMillis, 1L,
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
    @Scheduled(fixedDelayString = "${plugin.sql-generation.delta-sweep-ms:60000}")
    public void sweep() {
        wake();
    }

    private void drain() {
        try {
            while (queueService.processNextPending()) {
                // keep draining until the queue is empty
            }
        } catch (RuntimeException e) {
            log.warn("Delta SQL drain failed; the segment stays pending for the sweep: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
