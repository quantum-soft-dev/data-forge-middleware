package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.events.CheckpointRecordedEvent;
import com.bitbi.dfm.plugin.application.PluginAuditService;
import com.bitbi.dfm.plugin.application.PluginDeltaBaselineService;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Re-captures the Bit BI delta baselines automatically after a site history wipe (035 — issue #89).
 *
 * <p>The trigger is the first checkpoint built after the wipe, not the FULL_SNAPSHOT commit. At
 * commit time {@code DeltaRebaselineService.reset} has just deleted every checkpoint of the site in
 * the same transaction, so a recapture there would freeze baseline 0 for every table — and the
 * DELTA segments that follow would then generate SQL overlapping the checkpoint CSVs the plugin
 * client downloads, i.e. duplicate rows in Bit BI. Baselines have to be checkpoint seqs, exactly as
 * in a manual reinit.</p>
 *
 * <p>The window between the two is already handled and stays as it is: the sweep worker routes
 * FULL_SNAPSHOT segments to {@code suspendBaselines}, so no SQL is produced while there is nothing
 * consistent to base it on.</p>
 *
 * <p>Runs in its own transaction, so taking the {@code wipe_pending} flag and recapturing are
 * atomic — that is what makes the recapture happen exactly once per wipe, and what lets a failure
 * roll the flag back so the next checkpoint build retries. The exception is deliberately not
 * caught here; {@code CheckpointService} keeps listener failures away from the build itself.</p>
 *
 * <p>An ordinary re-baseline is deliberately untouched by this and keeps today's
 * suspend-then-manual-reinit behaviour.</p>
 */
@Component
public class DeltaWipeReinitListener {

    private static final Logger log = LoggerFactory.getLogger(DeltaWipeReinitListener.class);

    private static final String PLUGIN_ID = "bit-bi";

    private final DeltaSyncStateService syncStateService;
    private final SiteService siteService;
    private final AccountPluginRepository accountPluginRepository;
    private final PluginDeltaBaselineService baselineService;
    private final PluginAuditService auditService;

    public DeltaWipeReinitListener(DeltaSyncStateService syncStateService,
                                   SiteService siteService,
                                   AccountPluginRepository accountPluginRepository,
                                   PluginDeltaBaselineService baselineService,
                                   PluginAuditService auditService) {
        this.syncStateService = syncStateService;
        this.siteService = siteService;
        this.accountPluginRepository = accountPluginRepository;
        this.baselineService = baselineService;
        this.auditService = auditService;
    }

    /**
     * Consume a pending wipe, if this checkpoint is the first one after one.
     *
     * @param event the checkpoint that was just recorded
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCheckpointRecorded(CheckpointRecordedEvent event) {
        // Scoped to the epoch the event's build folded (issue #142). A build that finished just
        // before a wipe committed publishes its event after it, and taking the flag unscoped would
        // spend this wipe's pending re-init on a recapture that reads the checkpoints table the wipe
        // has just emptied — zero baselines frozen, and the first genuine post-wipe checkpoint left
        // with nothing to consume.
        if (!syncStateService.consumeWipePending(event.siteId(), event.baselineEpoch())) {
            return; // an ordinary checkpoint — the overwhelmingly common case
        }

        Site site = siteService.getSite(event.siteId());
        Optional<AccountPlugin> activation = accountPluginRepository
                .findByAccountIdAndPluginId(site.getAccountId(), PLUGIN_ID)
                .filter(AccountPlugin::isActive);

        if (activation.isEmpty()) {
            // Nothing to recapture for, and the flag is now cleared: a later activation captures
            // baselines for itself, so holding it would only arm a recapture for a future wipe.
            log.debug("Post-wipe checkpoint for site {} — no active bit-bi activation, nothing "
                    + "to recapture", event.siteId());
            return;
        }

        baselineService.recaptureForSite(activation.get(), site);
        auditService.logDeltaAutoReinit(site.getAccountId(), site.getId(), event.seq());
        log.info("Bit BI baselines re-captured automatically after the wipe of site {} at "
                + "checkpoint seq {}", event.siteId(), event.seq());
    }
}
