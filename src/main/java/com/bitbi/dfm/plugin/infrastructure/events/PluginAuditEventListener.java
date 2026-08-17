package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>Both listener methods run inside the publisher's transaction synchronization: {@code
 * afterCommit} and {@code afterCompletion} both fire <em>before</em> Spring unbinds the
 * publisher's {@code ConnectionHolder}. A write starting on that thread would therefore open its
 * own transaction while the thread still holds a connection, and ask the pool for a second one — the
 * hold-one-and-wait-for-another shape that turns a connection shortage into a stall rather than a
 * delay, and the one thing the pool sizing of #161 relies on background work never doing. Worse, an
 * exception from an {@code afterCommit} callback propagates to the caller of {@code commit()}, so a
 * connection timeout would surface as a failure of an operation that had already succeeded.</p>
 *
 * <p>Two things keep that from happening, and they are not redundant:</p>
 * <ol>
 *   <li>The hand-off goes to {@code pluginAuditExecutor}, whose rejection policy drops and logs
 *       rather than running the task on the caller's thread. That is what removes the defect: on
 *       {@code pluginExecutor} — 10 threads, 50 queue slots, {@code CallerRunsPolicy} — saturation
 *       handed the write straight back to the publisher.</li>
 *   <li>{@link #persist} refuses to write at all when a transaction is active on its own thread.
 *       The invariant is then enforced where it matters instead of being inferred from a pool's
 *       configuration, and it still holds if the {@code @Async} hand-off is ever removed or fails
 *       to apply.</li>
 * </ol>
 */
@Component
public class PluginAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditEventListener.class);

    private final PluginAuditEntryWriter writer;

    PluginAuditEventListener(PluginAuditEntryWriter writer) {
        this.writer = writer;
    }

    /**
     * Writes the entry once the publishing transaction has committed.
     *
     * @param event the deferred entry, and the entry to write instead on a rollback
     */
    @Async("pluginAuditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEntryReady(PluginAuditEntryReadyEvent event) {
        persist(event.entry(), "after commit");
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
    @Async("pluginAuditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onAuditEntryRolledBack(PluginAuditEntryReadyEvent event) {
        if (event.rollbackEntry() == null) {
            return;
        }
        persist(event.rollbackEntry(), "after rollback");
    }

    /**
     * Writes the entry in a transaction of its own, unless this thread already holds one.
     *
     * <p>The refusal is the last line of issue #171 and is deliberately silent about recovering:
     * the entry cannot be re-queued from here — the executor that would carry it is by definition
     * out of capacity — and an audit write must not become a reason for the publisher to wait or to
     * fail. So it is logged at ERROR and dropped, like every other audit write that cannot be
     * completed.</p>
     */
    private void persist(PluginAuditLog entry, String phase) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("Dropping deferred audit entry {}: {} plugin={} account={} — this thread is "
                            + "inside a transaction and still holds its connection, so writing here "
                            + "would take a second one (issue #171). The audit executor must have "
                            + "handed the write back to the publisher",
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
