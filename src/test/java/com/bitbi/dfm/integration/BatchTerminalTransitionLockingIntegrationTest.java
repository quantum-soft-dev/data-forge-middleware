package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 031 (T09) — a terminal transition must not be silently overwritten by a stale in-memory copy.
 *
 * <p>030/T06 made the timeout sweep a conditional bulk UPDATE, which fixed the sweeper killing live
 * sessions but left the mirror-image hole: the bulk UPDATE changed {@code status} without touching
 * {@code @Version}, so a session that had loaded the batch <em>before</em> the sweep could still
 * flush {@code COMPLETED} over it — its {@code WHERE id = ? AND version = ?} matched the untouched
 * version. The batch then ended COMPLETED while {@code BatchExpiredEvent} had already been
 * dispatched: two terminal events for one batch.</p>
 *
 * <p>The liveness touch stays version-free on purpose — it records activity, it does not change
 * state. Only state transitions take part in optimistic locking.</p>
 */
class BatchTerminalTransitionLockingIntegrationTest extends BaseIntegrationTest {

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        CommittedEventRecorder committedEventRecorder() {
            return new CommittedEventRecorder();
        }
    }

    /**
     * Records only events that survived their transaction. The production listeners are
     * {@code @TransactionalEventListener(AFTER_COMMIT)}, so an event published inside a transaction
     * that later rolls back never reaches a plugin — and must not count here either.
     */
    static class CommittedEventRecorder {
        private final List<Object> events = Collections.synchronizedList(new ArrayList<>());

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void onCompleted(BatchCompletedEvent event) {
            events.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void onExpired(BatchExpiredEvent event) {
            events.add(event);
        }

        List<Object> forBatch(UUID batchId) {
            synchronized (events) {
                return events.stream().filter(e -> batchId.equals(batchIdOf(e))).toList();
            }
        }

        void clear() {
            events.clear();
        }

        private static UUID batchIdOf(Object event) {
            if (event instanceof BatchCompletedEvent e) {
                return e.batchId();
            }
            if (event instanceof BatchExpiredEvent e) {
                return e.batchId();
            }
            return null;
        }
    }

    @Autowired
    private BatchLifecycleService batchLifecycleService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CommittedEventRecorder recorder;

    private TransactionTemplate txTemplate;
    private ExecutorService sweeperThread;
    private final List<UUID> createdSites = new ArrayList<>();
    private final List<UUID> createdAccounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        sweeperThread = Executors.newSingleThreadExecutor();
        recorder.clear();
    }

    @AfterEach
    void tearDown() {
        sweeperThread.shutdownNow();
        for (UUID siteId : createdSites) {
            jdbc.update("DELETE FROM batches WHERE site_id = ?", siteId);
            jdbc.update("DELETE FROM sites WHERE id = ?", siteId);
        }
        for (UUID accountId : createdAccounts) {
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
        createdSites.clear();
        createdAccounts.clear();
        recorder.clear();
    }

    @Test
    void staleSessionCannotCompleteABatchTheSweeperAlreadyReaped() {
        assertStaleTerminalWriteIsRejected(Batch::complete, "complete");
    }

    @Test
    void staleSessionCannotFailABatchTheSweeperAlreadyReaped() {
        assertStaleTerminalWriteIsRejected(Batch::fail, "fail");
    }

    @Test
    void exactlyOneTerminalEventEscapesPerBatch() {
        // The user-visible damage of the lost update: plugins received BATCH_EXPIRED and then
        // BATCH_COMPLETED for the same batch.
        UUID batchId = expiredBatch("events");
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);

        assertThrows(OptimisticLockingFailureException.class, () ->
                txTemplate.executeWithoutResult(tx -> {
                    Batch stale = batchRepository.findById(batchId).orElseThrow();
                    sweepInItsOwnTransaction(batchId, cutoff);
                    stale.complete();
                    batchRepository.save(stale);
                }));

        List<Object> escaped = recorder.forBatch(batchId);
        assertEquals(1, escaped.size(),
                "exactly one terminal event may escape per batch, got: " + escaped);
        assertInstanceOf(BatchExpiredEvent.class, escaped.get(0),
                "the sweeper's expiry is the one that happened");
    }

    @Test
    void liveSessionStillBeatsTheSweeper() {
        // 030/T06 regression sentinel — the version bump must not resurrect the original bug where
        // the sweeper reaps a batch a live session is still touching.
        UUID batchId = expiredBatch("live");
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);

        batchLifecycleService.touchActivity(batchId); // the session speaks up first

        assertEquals(false, batchLifecycleService.markBatchNotCompletedIfStillExpired(batchId, cutoff),
                "a revived batch is skipped, not reaped");
        assertEquals(BatchStatus.IN_PROGRESS, reload(batchId).getStatus());
        assertTrue(recorder.forBatch(batchId).isEmpty(), "no terminal event for a live batch");
    }

    @Test
    void silentBatchIsStillReaped() {
        UUID batchId = expiredBatch("silent");

        assertTrue(batchLifecycleService.markBatchNotCompletedIfStillExpired(
                        batchId, LocalDateTime.now().minusMinutes(60)),
                "a genuinely silent batch is still reclaimed");
        assertEquals(BatchStatus.NOT_COMPLETED, reload(batchId).getStatus());
    }

    @Test
    void heartbeatIsIgnoredOnceTheBatchIsTerminal() {
        // 031/T10: touchActivity updated by id alone, so a late gRPC frame stamped last_activity_at
        // onto a COMPLETED / FAILED / NOT_COMPLETED batch. Harmless to the sweeper (it filters on
        // IN_PROGRESS) but it fabricates evidence of a live session for whoever reads the row while
        // investigating an incident.
        for (BatchStatus terminal : List.of(BatchStatus.COMPLETED, BatchStatus.FAILED,
                BatchStatus.NOT_COMPLETED, BatchStatus.CANCELLED)) {
            UUID batchId = expiredBatch("term-" + terminal.name().toLowerCase());
            jdbc.update("UPDATE batches SET status = ?, completed_at = now() WHERE id = ?",
                    terminal.name(), batchId);
            LocalDateTime before = lastActivityOf(batchId);

            batchLifecycleService.touchActivity(batchId);

            assertEquals(before, lastActivityOf(batchId),
                    "a late heartbeat must not touch a " + terminal + " batch");
        }
    }

    @Test
    void heartbeatStillStampsALiveBatch() {
        UUID batchId = expiredBatch("livebeat");
        LocalDateTime before = lastActivityOf(batchId);

        batchLifecycleService.touchActivity(batchId);

        assertTrue(lastActivityOf(batchId).isAfter(before),
                "an IN_PROGRESS batch still records liveness");
    }

    private LocalDateTime lastActivityOf(UUID batchId) {
        return jdbc.queryForObject("SELECT last_activity_at FROM batches WHERE id = ?",
                LocalDateTime.class, batchId);
    }

    private void assertStaleTerminalWriteIsRejected(Consumer<Batch> transition, String tag) {
        UUID batchId = expiredBatch(tag);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);

        assertThrows(OptimisticLockingFailureException.class, () ->
                        txTemplate.executeWithoutResult(tx -> {
                            // The session loaded the batch before the sweep — this is the stale copy.
                            Batch stale = batchRepository.findById(batchId).orElseThrow();
                            sweepInItsOwnTransaction(batchId, cutoff);
                            transition.accept(stale);
                            batchRepository.save(stale); // flushes on commit, with the stale version
                        }),
                "a stale " + tag + " must not overwrite the sweeper's terminal transition");

        assertEquals(BatchStatus.NOT_COMPLETED, reload(batchId).getStatus(),
                "the sweeper's decision stands");
    }

    /** Run the sweep on another thread so it commits in its own transaction, like the real one. */
    private void sweepInItsOwnTransaction(UUID batchId, LocalDateTime cutoff) {
        try {
            sweeperThread.submit(
                            () -> batchLifecycleService.markBatchNotCompletedIfStillExpired(batchId, cutoff))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("sweep failed", e);
        }
    }

    private Batch reload(UUID batchId) {
        return txTemplate.execute(tx -> batchRepository.findById(batchId).orElseThrow());
    }

    private UUID expiredBatch(String tag) {
        UUID accountId = freshAccount(tag);
        UUID siteId = freshSite(accountId, tag);
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count,
                                     total_size, has_errors, started_at, created_at, last_activity_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', 'p/', 0, 0, false, ?, ?, ?)
                """, id, accountId, siteId, dbNow.minusMinutes(90), dbNow.minusMinutes(90),
                dbNow.minusMinutes(70));
        return id;
    }

    private UUID freshAccount(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, "031-" + tag + "-" + id + "@test.local", "031 " + tag);
        createdAccounts.add(id);
        return id;
    }

    private UUID freshSite(UUID accountId, String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, client_secret_hash, display_name,
                                   is_active, created_at, updated_at, site_name, client_api_version)
                VALUES (?, ?, ?, 'x', ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, 'V2')
                """, id, accountId, "031-" + tag + "-" + id + ".test.local", "031 " + tag,
                "031-" + tag + "-" + id);
        createdSites.add(id);
        return id;
    }
}
