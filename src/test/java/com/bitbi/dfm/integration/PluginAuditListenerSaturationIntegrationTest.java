package com.bitbi.dfm.integration;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Issue #171 — the deferred audit write must never happen on the thread that published the event.
 *
 * <p>That thread is inside the commit synchronization with its own {@code ConnectionHolder} still
 * bound (Spring releases it in {@code afterCompletion}, not {@code afterCommit}), so an audit write
 * starting there wants a <em>second</em> connection while holding one — the hold-one-and-wait-for-
 * another shape the pool sizing of #161 rules out everywhere else.</p>
 *
 * <p>Only the wired application can show it: the listener is invoked by Spring's transaction
 * synchronization, on the publishing thread, and the hand-off it performs there is what has to be
 * proven — the inline path appears only once the audit executor has no capacity left.</p>
 *
 * <p>It extends {@link BaseIntegrationTest} rather than {@link AbstractIntegrationTest} so that it
 * joins the context every other integration class already shares: a context of its own would be one
 * more cached context, and each one drains the queue workers once when it refreshes (the residual
 * noted in #167), which is exactly the kind of interference the suite has been paying down.</p>
 *
 * <p><b>What is observed is the row, not a mocked call.</b> An inline write has a signature nothing
 * else has: the entry is already committed by the time {@code TransactionTemplate.execute} returns,
 * because it was written by that very thread before it left {@code afterCommit}. A hand-off cannot
 * produce that — the publishing thread returns without having written anything.</p>
 */
@DisplayName("Deferred plugin audit under a saturated executor (#171)")
class PluginAuditListenerSaturationIntegrationTest extends BaseIntegrationTest {

    private static final String PLUGIN_ID = "bit-bi";

    @Autowired
    @Qualifier("pluginAuditExecutor")
    private ThreadPoolTaskExecutor auditExecutor;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    /** Held by every task occupying the executor; counted down in teardown. */
    private final CountDownLatch release = new CountDownLatch(1);

    private UUID accountId;

    @BeforeEach
    void useAnAccountOfItsOwn() {
        accountId = UUID.randomUUID();
    }

    @AfterEach
    void drainExecutor() {
        release.countDown();
        await().atMost(Duration.ofSeconds(30))
                .until(() -> auditExecutor.getThreadPoolExecutor().getActiveCount() == 0
                        && auditExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        jdbc.update("DELETE FROM plugin_audit_logs WHERE account_id = ?", accountId);
    }

    @Test
    @DisplayName("a saturated executor does not push the write onto the publishing thread")
    void shouldNotWriteOnThePublishingThreadWhenTheExecutorIsSaturated() {
        occupyEverySlot();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new PluginAuditEntryReadyEvent(entry())));

        assertThat(writtenEntries())
                .as("an entry already committed the moment the publishing thread returns can only "
                        + "have been written by that thread, inside afterCommit, while it still held "
                        + "the connection of the transaction that just committed")
                .isZero();

        // And it stays absent: with no capacity left the entry is dropped and logged, rather than
        // waiting for a slot — an audit write must not become a reason for the publisher to wait.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
                .until(() -> writtenEntries() == 0L);
    }

    @Test
    @DisplayName("with capacity the entry is still written")
    void shouldWriteTheEntryWhenTheExecutorHasCapacity() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new PluginAuditEntryReadyEvent(entry())));

        await().atMost(Duration.ofSeconds(10)).until(() -> writtenEntries() == 1L);
    }

    private PluginAuditLog entry() {
        return PluginAuditLog.success(PLUGIN_ID, accountId, PluginActionType.ACTIVATE);
    }

    private long writtenEntries() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_audit_logs WHERE account_id = ?", Long.class, accountId);
        return count == null ? 0L : count;
    }

    /**
     * Fills the executor until it refuses, so the next task can only be rejected.
     *
     * <p>Submitting exactly {@code maxPoolSize + queueCapacity} tasks would not be enough: this is
     * the context's own singleton, shared with every other integration class, so an audit write
     * left in flight by an earlier test can occupy a slot at fill time and free it a moment later —
     * after which the published entry would be queued rather than rejected, and this class would go
     * red for a reason that has nothing to do with what it asserts.</p>
     */
    private void occupyEverySlot() {
        while (true) {
            try {
                auditExecutor.execute(this::awaitRelease);
            } catch (TaskRejectedException e) {
                break;
            }
        }
        assertThat(auditExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
                .as("the executor refused a task, so its queue must be full")
                .isZero();
        assertThat(auditExecutor.getThreadPoolExecutor().getActiveCount())
                .isEqualTo(auditExecutor.getThreadPoolExecutor().getMaximumPoolSize());
    }

    private void awaitRelease() {
        try {
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("a task occupying the audit executor was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
