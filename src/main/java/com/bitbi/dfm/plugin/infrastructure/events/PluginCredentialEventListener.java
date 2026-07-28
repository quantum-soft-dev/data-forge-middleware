package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.domain.PluginCredentialRotatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes the audit entry for a credential rotation once the rotation itself has committed.
 *
 * <p>The audit write is {@code @Async} in its own transaction. Invoked directly from the rotating
 * service it commits independently, so a caller that fails after rotating would leave an audit row
 * describing a rotation the database rolled back. Running AFTER_COMMIT ties the record to the fact.</p>
 *
 * <p>{@code fallbackExecution} keeps non-transactional publishers working, matching
 * {@link BatchEventListener}.</p>
 */
@Component
public class PluginCredentialEventListener {

    private static final Logger log = LoggerFactory.getLogger(PluginCredentialEventListener.class);

    private final PluginAuditService pluginAuditService;

    public PluginCredentialEventListener(PluginAuditService pluginAuditService) {
        this.pluginAuditService = pluginAuditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCredentialRotated(PluginCredentialRotatedEvent event) {
        log.debug("Credential rotation committed: plugin={} account={} action={}",
                event.pluginId(), event.accountId(), event.actionType());

        pluginAuditService.logCredentialRotated(event.pluginId(), event.accountId(), event.actionType());
    }
}
