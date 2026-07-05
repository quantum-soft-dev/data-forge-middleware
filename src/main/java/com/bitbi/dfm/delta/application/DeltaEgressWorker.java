package com.bitbi.dfm.delta.application;

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
 * Bounded worker pool draining the delta Parquet egress queue (Delta Client v2 — 022, Task 8).
 *
 * <p>A session commit {@link #wake() wakes} the pool; each drain claims the next pending segment
 * (per-site head, {@code FOR UPDATE SKIP LOCKED} — see
 * {@link com.bitbi.dfm.delta.domain.ChangelogSegmentRepository#findNextPendingEgress}) and
 * materializes it, looping until the queue is empty. Different sites drain in parallel up to
 * {@code delta.egress.max-concurrent}; one site's segments always publish in seq order. Extra
 * wakes while the pool is saturated are discarded — a queued drain will see the new segment.
 * A low-frequency sweep re-wakes the pool for segments left pending by a crash or a failed
 * drain (the failed segment's transaction rolls back, so it stays queued).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaEgressWorker {

    private static final Logger log = LoggerFactory.getLogger(DeltaEgressWorker.class);

    private final DeltaEgressService egressService;
    private final ThreadPoolExecutor pool;

    public DeltaEgressWorker(DeltaEgressService egressService,
                             @Value("${delta.egress.max-concurrent:2}") int maxConcurrent) {
        this.egressService = egressService;
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxConcurrent),
                runnable -> {
                    Thread thread = new Thread(runnable, "delta-egress-" + threadNumber.incrementAndGet());
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
     * Fallback sweep: picks up segments missed by the commit-time wake (instance crash mid-drain,
     * failed egress, or a backlog predating this instance).
     */
    @Scheduled(fixedDelayString = "${delta.egress.sweep-ms:60000}")
    public void sweep() {
        wake();
    }

    private void drain() {
        try {
            while (egressService.egressNextPending()) {
                // keep draining until the queue is empty
            }
        } catch (RuntimeException e) {
            log.warn("Delta egress drain failed; the segment stays pending for the sweep: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
