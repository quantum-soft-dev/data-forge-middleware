package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Persists deferred audit entries once the transaction that produced them has committed.
 *
 * <p>Ties the record to the fact: an entry describing a state change is written only if that change
 * actually became durable. {@code fallbackExecution} keeps non-transactional publishers working,
 * matching {@link BatchEventListener}.</p>
 *
 * <p>Where the change had side effects a rollback cannot take back, the event carries a second
 * entry that is written on that path instead — see {@code PluginAuditEntryReadyEvent}.</p>
 *
 * <p>As everywhere else in the audit path, a failure here is swallowed — auditing must never break
 * the operation it records.</p>
 *
 * <h2>Never on the publishing thread (issue #171)</h2>
 *
 * <p>Both listener methods are invoked inside the publisher's transaction synchronization:
 * {@code afterCommit} and {@code afterCompletion} both fire <em>before</em> Spring unbinds the
 * publisher's {@code ConnectionHolder}. A <em>write</em> starting on that thread would therefore
 * open its own transaction while the thread still holds a connection, and ask the pool for a second
 * one — the hold-one-and-wait-for-another shape that turns a connection shortage into a stall
 * rather than a delay, and the one thing the pool sizing of #161 relies on background work never
 * doing. Worse, an exception from an {@code afterCommit} callback propagates to the caller of
 * {@code commit()}, so a connection timeout would surface as a failure of an operation that had
 * already succeeded.</p>
 *
 * <p>So the listener body does one thing on the publishing thread: hand the entry to
 * {@code pluginAuditExecutor}. Two things then keep the write off that thread, and they are not
 * redundant:</p>
 * <ol>
 *   <li>The hand-off is an explicit {@code execute} rather than {@code @Async}, so a full executor
 *       is a {@link RejectedExecutionException} <em>here</em>, where the entry is still in hand and
 *       can be named in the log. A rejection from an {@code @Async} {@code void} method is raised at
 *       submit time on this same thread but carries only the pool's statistics, and
 *       {@code CallerRunsPolicy} — which is what {@code pluginExecutor} used to do with these
 *       writes — would hand the write straight back.</li>
 *   <li>{@link #persist} refuses to write when a transaction is active on the thread it ends up
 *       running on. The invariant is then enforced where the write happens instead of being
 *       inferred from an executor's configuration.</li>
 * </ol>
 */
@Component
public class PluginAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditEventListener.class);

    private final PluginAuditEntryWriter writer;
    private final Executor auditExecutor;

    PluginAuditEventListener(PluginAuditEntryWriter writer,
                             @Qualifier("pluginAuditExecutor") Executor auditExecutor) {
        this.writer = writer;
        this.auditExecutor = auditExecutor;
    }

    /**
     * Writes the entry once the publishing transaction has committed.
     *
     * @param event the deferred entry, and the entry to write instead on a rollback
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEntryReady(PluginAuditEntryReadyEvent event) {
        handOff(event.entry(), "after commit");
    }

    /**
     * Records what a rollback could not undo.
     *
     * <p>Most deferred entries have no rollback entry — the change is wholly transactional, so a
     * rollback leaves nothing worth writing. Clear, reinit and delete-generation are different:
     * their S3 objects are gone before the commit point, so a rollback restores the rows over
     * files that no longer exist, and that divergence must not pass silently.</p>
     *
     * <p>No {@code fallbackExecution} here, unlike the commit phase: a non-transactional publisher
     * never rolls back, and firing on it would write the failure entry for every such caller.</p>
     *
     * @param event the deferred entry, and the entry to write instead on a rollback
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onAuditEntryRolledBack(PluginAuditEntryReadyEvent event) {
        if (event.rollbackEntry() == null) {
            return;
        }
        handOff(event.rollbackEntry(), "after rollback");
    }

    /**
     * Puts the write on the audit executor, and never lets anything escape to the publisher.
     *
     * <p>This runs on the publishing thread, past the commit point, so an exception here would
     * reach the caller of {@code commit()} as a failure of an operation that has already
     * succeeded.</p>
     */
    private void handOff(PluginAuditLog entry, String phase) {
        try {
            auditExecutor.execute(() -> persist(entry, phase));
        } catch (RejectedExecutionException e) {
            // The entry is named here because the rejection is the only place it can be: the task
            // never runs, so persist() never logs it. Filling this queue means the writes are not
            // draining, which is a database problem — re-queueing would only deepen it, and waiting
            // is what an audit write must never make the publisher do.
            log.error("Dropping deferred audit entry {}: {} plugin={} account={} — the audit "
                            + "executor has no capacity left. The entry is lost; the operation it "
                            + "describes is not affected",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId());
        } catch (Exception e) {
            log.error("Failed to hand off deferred audit entry {}: {} plugin={} account={}",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId(), e);
        }
    }

    /**
     * Writes the entry in a transaction of its own, unless this thread already holds one.
     *
     * <p>On the executor that refusal is unreachable, which is the point of it: it is what makes
     * the invariant a property of the write rather than of the executor wired in front of it. It
     * becomes reachable again if this listener is ever invoked directly, or handed an executor that
     * runs tasks on the caller's thread — which is exactly how issue #171 happened.</p>
     */
    private void persist(PluginAuditLog entry, String phase) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("Dropping deferred audit entry {}: {} plugin={} account={} — the write reached "
                            + "a thread that is inside a transaction and still holds its connection, "
                            + "so writing here would take a second one (issue #171). The audit "
                            + "executor is running tasks on the thread that submits them",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId());
            return;
        }
        try {
            writer.write(entry);
            log.debug("Audit logged {}: {} plugin={} account={}",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId());
        } catch (Exception e) {
            // The exception itself, not only its message: an audit write that cannot be completed
            // is invisible everywhere else, and several of the failures reachable here (a missing
            // partition, a rejected proxy) carry no message at all.
            log.error("Failed to persist deferred audit entry {}: {} plugin={} account={}",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId(), e);
        }
    }
}
