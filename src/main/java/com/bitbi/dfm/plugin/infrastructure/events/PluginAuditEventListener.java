package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 */
@Component
public class PluginAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditEventListener.class);

    private final PluginAuditLogRepository auditLogRepository;

    public PluginAuditEventListener(PluginAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    // REQUIRES_NEW keeps the write correct even if @Async is ever removed: a synchronous
    // AFTER_COMMIT listener running with REQUIRED would join the publisher's already-completed
    // transaction and lose the row. On the async path it is simply equivalent to REQUIRED.
    @Async("pluginExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
     */
    @Async("pluginExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onAuditEntryRolledBack(PluginAuditEntryReadyEvent event) {
        if (event.rollbackEntry() == null) {
            return;
        }
        persist(event.rollbackEntry(), "after rollback");
    }

    private void persist(PluginAuditLog entry, String phase) {
        try {
            auditLogRepository.save(entry);
            log.debug("Audit logged {}: {} plugin={} account={}",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId());
        } catch (Exception e) {
            log.error("Failed to persist deferred audit entry {}: {} plugin={} account={} error={}",
                    phase, entry.getActionType(), entry.getPluginId(), entry.getAccountId(), e.getMessage());
        }
    }
}
