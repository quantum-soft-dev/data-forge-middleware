package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 030 (T01) — the Delta v2 liveness touch must never collide with a concurrent batch transition.
 *
 * <p>{@code touchActivity} used to be a {@code findById} + {@code save()} on a {@code @Version}ed
 * aggregate, so a timeout sweeper (or a segment commit) racing the touch made one side throw
 * {@link org.springframework.dao.OptimisticLockingFailureException} — and when the loser was the
 * touch, the exception surfaced in the gRPC ingest path and killed a healthy live session. The
 * touch is now a targeted {@code UPDATE ... SET last_activity_at} that does not take part in
 * optimistic locking.</p>
 */
class DeltaSessionLivenessIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BatchLifecycleService batchLifecycleService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentTouchesDoNotBreakBatchCompletion() throws Exception {
        assertTerminalTransitionSurvivesTouchStorm(
                batchLifecycleService::completeBatch, BatchStatus.COMPLETED, "complete");
    }

    @Test
    void concurrentTouchesDoNotBreakBatchFailure() throws Exception {
        assertTerminalTransitionSurvivesTouchStorm(
                batchLifecycleService::failBatch, BatchStatus.FAILED, "fail");
    }

    @Test
    void concurrentTouchesDoNotBreakTimeoutSweep() throws Exception {
        // The sweeper is the transition that actually races a live session in production.
        assertTerminalTransitionSurvivesTouchStorm(
                batchLifecycleService::markBatchNotCompleted, BatchStatus.NOT_COMPLETED, "sweep");
    }

    private void assertTerminalTransitionSurvivesTouchStorm(
            Consumer<UUID> transition, BatchStatus expected, String tag) throws Exception {

        UUID accountId = freshAccount(tag);
        UUID siteId = freshV2Site(accountId, tag);
        UUID batchId = batchLifecycleService.startBatch(accountId, siteId).getId();

        int touchers = 6;
        int touchesPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(touchers + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> escaped = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < touchers; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int n = 0; n < touchesPerThread; n++) {
                        // This is the ingest path's call: it must never throw, before or after the
                        // batch turns terminal underneath it.
                        batchLifecycleService.touchActivity(batchId);
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                go.await();
                Thread.sleep(10); // let the touch storm get going, then transition mid-flight
                transition.accept(batchId);
                return null;
            }));
            go.countDown();

            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    escaped.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(escaped.isEmpty(),
                "neither the liveness touch nor the " + tag + " transition may throw: " + escaped);

        Batch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertEquals(expected, reloaded.getStatus(), "the terminal transition wins the race");
        assertNotNull(reloaded.getLastActivityAt(), "liveness was recorded");
    }

    // ---------------------------------------------------------------- helpers

    private UUID freshAccount(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, "030-" + tag + "-" + id + "@test.local", "030 " + tag);
        return id;
    }

    private UUID freshV2Site(UUID accountId, String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, id, accountId, "030-" + tag + "-" + id + ".test.local", "030 " + tag,
                "030-" + tag + "-" + id);
        return id;
    }
}
