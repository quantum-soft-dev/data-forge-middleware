package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Autowired
    private BatchLifecycleService batchLifecycleService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;
    private ExecutorService sweeperThread;
    private final List<UUID> createdSites = new ArrayList<>();
    private final List<UUID> createdAccounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        sweeperThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        sweeperThread.shutdownNow();
        for (UUID siteId : createdSites) {
            // The two non-cascading references to batches, cleared by the relationship each
            // constraint actually uses (issue #226). This class seeds no segments and no
            // activations today, so neither delete has anything to do -- but that is a property of
            // the current tests, not of the cleanup, and "safe because nobody writes one yet" is
            // exactly how #226 sat latent. The fixture and DeltaSessionLivenessIntegrationTest
            // sweep both relationships; this is the third cleanup of the same shape.
            jdbc.update("DELETE FROM changelog_segments WHERE site_id = ? OR batch_id IN "
                    + "(SELECT id FROM batches WHERE site_id = ?)", siteId, siteId);
            jdbc.update("DELETE FROM account_plugins WHERE baseline_batch_id IN "
                    + "(SELECT id FROM batches WHERE site_id = ?)", siteId);
            jdbc.update("DELETE FROM device_authorizations WHERE site_id = ?", siteId);
            jdbc.update("DELETE FROM batches WHERE site_id = ?", siteId);
            jdbc.update("DELETE FROM sites WHERE id = ?", siteId);
        }
        for (UUID accountId : createdAccounts) {
            // Same hole as DeltaSessionLivenessIntegrationTest.cleanUpSeededData (issue #228):
            // a site_id-only batches sweep leaves a mismatched account_id row that blocks
            // DELETE FROM accounts. Children of leftover sites (error_logs.site_id, batches
            // of those sites) have to go first, or the new DELETE FROM sites WHERE account_id
            // is itself the next ScriptStatementFailedException. This class seeds neither
            // today; "safe because nobody writes one yet" is how #226 sat latent.
            jdbc.update("DELETE FROM device_authorizations WHERE account_id = ? OR site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?)", accountId, accountId);
            jdbc.update("DELETE FROM error_logs WHERE site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?)", accountId);
            jdbc.update("DELETE FROM changelog_segments WHERE site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?) OR batch_id IN "
                    + "(SELECT id FROM batches WHERE account_id = ? OR site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?))",
                    accountId, accountId, accountId);
            jdbc.update("DELETE FROM account_plugins WHERE baseline_batch_id IN "
                    + "(SELECT id FROM batches WHERE account_id = ? OR site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?))", accountId, accountId);
            jdbc.update("DELETE FROM batches WHERE account_id = ? OR site_id IN "
                    + "(SELECT id FROM sites WHERE account_id = ?)", accountId, accountId);
            jdbc.update("DELETE FROM sites WHERE account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
        createdSites.clear();
        createdAccounts.clear();
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
    void onlyTheSweepersTerminalEventSurvivesTheRace() {
        // The user-visible damage of the lost update was plugins receiving BATCH_EXPIRED and then
        // BATCH_COMPLETED for the same batch.
        //
        // The completing side publishes its event before the flush, so the guarantee is not "the
        // event is never published" but "it is never delivered": the transaction rolls back, and
        // BatchEventListener consumes both events at TransactionPhase.AFTER_COMMIT. This test proves
        // the rollback; BatchEventListenerPhaseTest pins the AFTER_COMMIT phase that makes the
        // rollback sufficient. (Asserting delivery directly would need an extra bean and therefore an
        // extra Spring context, which exhausts the shared Postgres connection limit in a full run.)
        UUID batchId = expiredBatch("events");
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(60);

        assertThrows(OptimisticLockingFailureException.class, () ->
                txTemplate.executeWithoutResult(tx -> {
                    Batch stale = batchRepository.findById(batchId).orElseThrow();
                    sweepInItsOwnTransaction(batchId, cutoff);
                    stale.complete();
                    batchRepository.save(stale);
                }));

        assertEquals(BatchStatus.NOT_COMPLETED, reload(batchId).getStatus(),
                "the expiry is the terminal transition that actually happened");
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
