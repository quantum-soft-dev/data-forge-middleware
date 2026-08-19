package com.bitbi.dfm.integration;

import com.bitbi.dfm.testsupport.LockWaitBound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * Issue #197 — a statement blocked on a lock must fail and name itself, not stop the run.
 *
 * <p>The integration classes share one PostgreSQL database across every cached Spring context, and
 * every context keeps its background workers alive for the rest of the run. A statement of the
 * class under test — {@code @Sql("/test-data.sql")} deleting the {@code %.example.com} rows, a
 * {@code clearPluginSqlGenerations} delete, an ordinary repository write — can therefore wait on a
 * lock a sibling context holds. PostgreSQL's default {@code lock_timeout} is 0, so that wait has no
 * end: the JUnit thread never returns, the suite stops instead of failing, and CI kills the job
 * without saying which test was stuck. That is the residual behind #159's
 * {@code ScriptStatementFailedException} and the reason a test-side drain deadline (#175) is not
 * enough — a call already blocked inside the database is not interruptible from the test.</p>
 *
 * <p>This is the wired half of the guard: it reads the bound a <em>pooled</em> connection actually
 * carries (a file can only show what was declared) and then produces a genuinely blocked statement
 * and requires it to be aborted. {@code LockWaitBoundTestProfileTest} is the static half on the
 * fast gate.</p>
 *
 * <p><b>The probe blocks on an advisory lock, not on a row.</b> {@code lock_timeout} is one GUC
 * over the whole lock manager — PostgreSQL applies it to "a table, index, row, or other database
 * object" alike, and nothing configures the kinds separately — so an advisory lock proves the bound
 * for the row locks this ticket is about. What it buys is that the probe cannot become the hazard
 * it tests for: a literal key is one lock in this shared database that no other class, and no
 * background worker, can be holding or waiting on (both production advisory locks derive their keys
 * through {@code hashtextextended}), and it leaves no row behind for the next class to count.</p>
 */
@DisplayName("Lock wait bound on a live connection (#197)")
class DatabaseLockWaitBoundIntegrationTest extends BaseIntegrationTest {

    /**
     * Key of the advisory lock this test contends on. Arbitrary and deliberately small: every
     * advisory lock in production is keyed through {@code hashtextextended}, so nothing else in a
     * run can name it.
     */
    private static final long PROBE_LOCK_KEY = 197_197_197L;

    /**
     * Head-room over the bound before the probe gives up and reports the run as unbounded. It is
     * counted from the moment the blocked statement exists — see {@code shouldAbort...} — so it
     * covers the abort and nothing else: a connection this test had to wait for is not part of it.
     */
    private static final Duration MARGIN = Duration.ofSeconds(15);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a pooled connection carries the bound, not just application-test.yml")
    void shouldApplyTheLockTimeoutToPooledConnections() {
        LockWaitBound.assertBoundsALockWait("a pooled connection", configuredBound());
    }

    @Test
    @DisplayName("a statement blocked on a lock is aborted, and says why")
    void shouldAbortAStatementBlockedOnALockInsteadOfWaitingForEver() throws Exception {
        Duration bound = configuredBound();
        TransactionTemplate holderTransaction = new TransactionTemplate(transactionManager);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch statementIsOpen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        AtomicReference<Statement> blockedStatement = new AtomicReference<>();

        try {
            Future<?> holder = threads.submit(() -> holderTransaction.execute(status -> {
                jdbc.execute("SELECT pg_advisory_xact_lock(" + PROBE_LOCK_KEY + ")");
                held.countDown();
                awaitRelease(release, bound);
                return null;
            }));
            requireHeld(held, holder);

            Future<Throwable> blocked = threads.submit(() -> catchThrowable(() ->
                    jdbc.execute((StatementCallback<Void>) statement -> {
                        blockedStatement.set(statement);
                        // The callback runs once the connection is in hand, which is where the
                        // bounded wait below must start from: the holder pins one of the four
                        // connections this profile allows, and Hikari waits 30 s for one — longer
                        // than the budget for the abort itself, so a busy pool would otherwise be
                        // reported as an unbounded lock wait.
                        statementIsOpen.countDown();
                        statement.execute("SELECT pg_advisory_xact_lock(" + PROBE_LOCK_KEY + ")");
                        return null;
                    })));
            requireStatementOpen(statementIsOpen, blocked);

            Throwable failure = awaitOutcome(blocked, bound, blockedStatement);
            SQLException reported = lockTimeout(failure);

            assertThat(reported.getSQLState())
                    .as("a blocked statement must be aborted by lock_timeout (55P03), and this one "
                            + "failed for another reason: " + failure)
                    .isEqualTo("55P03");
            assertThat(reported.getMessage())
                    .as("the abort has to arrive as a described failure — the wording is the "
                            + "server's and follows its lc_messages ('canceling statement due to "
                            + "lock timeout' in English), which is why the contract asserted above "
                            + "is the SQLSTATE rather than the text (#197)")
                    .isNotBlank();
            assertThat(holder.isDone())
                    .as("the holder must still be holding: a probe that failed after the lock was "
                            + "released would prove nothing")
                    .isFalse();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    /**
     * Fails with the holder's own cause when it never took the lock. Without this the only thing
     * reported would be "nothing was blocked", sending the reader to the advisory lock rather than
     * to the pool timeout or rolled-back transaction that actually happened.
     */
    private static void requireHeld(CountDownLatch held, Future<?> holder) throws Exception {
        if (held.await(60, TimeUnit.SECONDS)) {
            return;
        }
        if (holder.isDone()) {
            holder.get(10, TimeUnit.SECONDS);
        }
        fail("the probe never acquired its own lock, so nothing was blocked and this test proves "
                + "nothing about the bound (#197)");
    }

    /**
     * Waits until the probe holds a statement to block on, and fails with the probe's own cause
     * when it never gets one. The task returns its failure rather than throwing it, so a pool that
     * could not hand out a connection — four of them here, one pinned by the holder for the whole
     * test — is reported as itself instead of as an unbounded lock wait two minutes later.
     */
    private static void requireStatementOpen(CountDownLatch statementIsOpen,
                                             Future<Throwable> blocked) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!statementIsOpen.await(1, TimeUnit.SECONDS)) {
            if (blocked.isDone()) {
                fail("the probe never reached a statement to block on: " + blocked.get());
            }
            if (System.nanoTime() > deadline) {
                fail("the probe never got a pooled connection to block on, and is still waiting "
                        + "for one — nothing here says anything about the bound (#197)");
            }
        }
    }

    /**
     * Waits for the blocked statement to fail. A wait that outlives the bound plus {@link #MARGIN}
     * is the regression this class exists for, and it is reported as a failure rather than left to
     * hang — the statement is cancelled first so the rest of the run is not blocked behind it.
     */
    private Throwable awaitOutcome(Future<Throwable> blocked, Duration bound,
                                   AtomicReference<Statement> blockedStatement) throws Exception {
        try {
            return blocked.get(bound.plus(MARGIN).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelQuietly(blockedStatement.get());
            return fail("a statement blocked on a lock was still waiting after "
                    + bound.plus(MARGIN) + ": the test profile does not bound a lock wait, so a "
                    + "collision between two cached contexts stops the run instead of failing a "
                    + "test (#197)");
        }
    }

    /** The bound a pooled connection carries, read from the server rather than from the profile. */
    private Duration configuredBound() {
        Long millis = jdbc.queryForObject(
                "SELECT setting::bigint FROM pg_settings WHERE name = 'lock_timeout'", Long.class);
        assertThat(millis).as("PostgreSQL always reports lock_timeout").isNotNull();
        return Duration.ofMillis(millis);
    }

    private static SQLException lockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException;
            }
        }
        return fail("the blocked statement did not fail with a SQLException at all: " + failure);
    }

    /**
     * Holds the lock until the probe is done with it. The latch is counted down in the test's
     * {@code finally}, so this ceiling is only reached when something went wrong — it has to
     * outlast every wait the probe can legitimately spend, the pool wait included, or the holder
     * would release early and the assertions would be judging a lock nobody held.
     */
    private static void awaitRelease(CountDownLatch release, Duration bound) {
        try {
            release.await(bound.plus(MARGIN).plusMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void cancelQuietly(Statement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // Best effort: the point of cancelling is to keep the run going after the failure below.
        }
    }
}
