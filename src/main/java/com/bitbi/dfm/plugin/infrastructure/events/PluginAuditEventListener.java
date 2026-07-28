package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginAuditEntryReadyEvent;
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

    // REQUIRES_NEW is mandatory, not stylistic: by AFTER_COMMIT the publishing transaction has
    // finished, so the listener must open its own. Spring rejects any other propagation here.
    @Async("pluginExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEntryReady(PluginAuditEntryReadyEvent event) {
        try {
            auditLogRepository.save(event.entry());
            log.debug("Audit logged after commit: {} plugin={} account={}",
                    event.entry().getActionType(), event.entry().getPluginId(), event.entry().getAccountId());
        } catch (Exception e) {
            log.error("Failed to persist deferred audit entry: {} plugin={} account={} error={}",
                    event.entry().getActionType(), event.entry().getPluginId(),
                    event.entry().getAccountId(), e.getMessage());
        }
    }
}
