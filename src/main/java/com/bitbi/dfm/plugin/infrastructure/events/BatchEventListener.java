package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.application.PluginEventDispatcher;
import com.bitbi.dfm.plugin.domain.PluginEvent;
import com.bitbi.dfm.shared.domain.events.BatchCompletedEvent;
import com.bitbi.dfm.shared.domain.events.BatchExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event listener that bridges domain events to plugin event dispatch.
 *
 * <p>Listens for batch-related domain events and creates corresponding
 * PluginEvents for dispatch to subscribed plugins.</p>
 *
 * <p>User Story 5 (Phase 7) - Receive Batch Completion Notifications</p>
 *
 * <p>Supported events:</p>
 * <ul>
 *   <li>BatchCompletedEvent → BATCH_COMPLETED</li>
 *   <li>BatchExpiredEvent → BATCH_EXPIRED</li>
 * </ul>
 */
@Component
public class BatchEventListener {

    private static final Logger log = LoggerFactory.getLogger(BatchEventListener.class);

    private final PluginEventDispatcher pluginEventDispatcher;

    public BatchEventListener(PluginEventDispatcher pluginEventDispatcher) {
        this.pluginEventDispatcher = pluginEventDispatcher;
    }

    /**
     * Handles batch completion events.
     *
     * <p>Creates a BATCH_COMPLETED plugin event and dispatches it to all
     * subscribed plugins that have active activations for this account.</p>
     *
     * <p>Requires accountId to be present in the event (added in Phase 7).
     * Events without accountId are logged and skipped.</p>
     *
     * <p>Runs AFTER_COMMIT: {@code BatchLifecycleService.completeBatch} publishes from inside
     * the caller's transaction (for Delta v2, {@code DeltaSessionCommitTransaction.commit} — the
     * async dispatch must not run before the changelog segment row and COMPLETED status are
     * committed). {@code fallbackExecution} keeps non-transactional publishers working.</p>
     *
     * @param event the batch completion domain event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBatchCompleted(BatchCompletedEvent event) {
        log.debug("Received BatchCompletedEvent: batchId={}, accountId={}",
                event.batchId(), event.accountId());

        if (event.accountId() == null) {
            log.warn("BatchCompletedEvent missing accountId - cannot dispatch to plugins: batchId={}",
                    event.batchId());
            return;
        }

        PluginEvent pluginEvent = PluginEvent.batchCompleted(
                event.accountId(),
                event.batchId(),
                event.uploadedFilesCount(),
                event.totalSize()
        );

        log.info("Dispatching BATCH_COMPLETED event to plugins: batchId={}, accountId={}",
                event.batchId(), event.accountId());

        pluginEventDispatcher.dispatch(pluginEvent);
    }

    /**
     * Handles batch expiration events.
     *
     * <p>Creates a BATCH_EXPIRED plugin event and dispatches it to all
     * subscribed plugins that have active activations for this account.</p>
     *
     * <p>Requires accountId to be present in the event.
     * Events without accountId are logged and skipped.</p>
     *
     * @param event the batch expired domain event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBatchExpired(BatchExpiredEvent event) {
        log.debug("Received BatchExpiredEvent: batchId={}, accountId={}",
                event.batchId(), event.accountId());

        if (event.accountId() == null) {
            log.warn("BatchExpiredEvent missing accountId - cannot dispatch to plugins: batchId={}",
                    event.batchId());
            return;
        }

        PluginEvent pluginEvent = PluginEvent.batchExpired(
                event.accountId(),
                event.batchId()
        );

        log.info("Dispatching BATCH_EXPIRED event to plugins: batchId={}, accountId={}",
                event.batchId(), event.accountId());

        pluginEventDispatcher.dispatch(pluginEvent);
    }
}
