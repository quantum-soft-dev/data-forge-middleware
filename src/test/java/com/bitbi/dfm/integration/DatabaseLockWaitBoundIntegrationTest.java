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

    /** Head-room over the bound before the probe gives up and reports the run as unbounded. */
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
            assertThat(held.await(30, TimeUnit.SECONDS))
                    .as("the probe never acquired its own lock, so nothing was blocked")
                    .isTrue();

            Future<Throwable> blocked = threads.submit(() -> catchThrowable(() ->
                    jdbc.execute((StatementCallback<Void>) statement -> {
                        blockedStatement.set(statement);
                        statement.execute("SELECT pg_advisory_xact_lock(" + PROBE_LOCK_KEY + ")");
                        return null;
                    })));

            Throwable failure = awaitOutcome(blocked, bound, blockedStatement);
            SQLException reported = lockTimeout(failure);

            assertThat(reported.getSQLState())
                    .as("a blocked statement must be aborted by lock_timeout (55P03), and this one "
                            + "failed for another reason: " + failure)
                    .isEqualTo("55P03");
            assertThat(reported.getMessage())
                    .as("the failure has to name the cause, otherwise the next reader is back to "
                            + "guessing why a test died (#197)")
                    .contains("lock timeout");
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

    private static void awaitRelease(CountDownLatch release, Duration bound) {
        try {
            release.await(bound.plus(MARGIN).plusSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
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
