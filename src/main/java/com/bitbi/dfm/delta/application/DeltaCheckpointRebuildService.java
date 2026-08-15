package com.bitbi.dfm.delta.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Forced out-of-schedule checkpoint rebuild (feature 023 — Delta Sync UI, B7).
 *
 * <p>Admin-triggered alternative to the nightly {@link CheckpointScheduler}: sets the persistent
 * {@code rebuild_requested} flag (surfaced in the UI as "Rebuild queued"), runs
 * {@link CheckpointService#buildCheckpoint}({@code siteId}, {@code true}) on a dedicated
 * single-thread executor so forced rebuilds serialize, and always clears the flag when the
 * attempt finishes. The {@code force} flag rematerializes from the existing frame even when
 * there are no new segments (issue #128). The checkpoint pointer is monotonic
 * ({@link DeltaSyncStateService#recordCheckpoint}), so a collision with a concurrent scheduled
 * build cannot regress state.</p>
 *
 * <p>The flag is a durable DB row while the queued task is in-memory only (review r3), so the
 * service short-circuits duplicate requests, clears the flag when the executor rejects the
 * task, and re-drives flagged sites on startup — otherwise the "Rebuild queued" chip would
 * stick forever after a restart or a full queue.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaCheckpointRebuildService {

    private static final Logger log = LoggerFactory.getLogger(DeltaCheckpointRebuildService.class);

    private final DeltaSyncStateService syncStateService;
    private final CheckpointService checkpointService;
    private final Executor rebuildExecutor;

    public DeltaCheckpointRebuildService(DeltaSyncStateService syncStateService,
                                         CheckpointService checkpointService,
                                         @Qualifier("deltaRebuildExecutor") Executor rebuildExecutor) {
        this.syncStateService = syncStateService;
        this.checkpointService = checkpointService;
        this.rebuildExecutor = rebuildExecutor;
    }

    /**
     * Queue a forced checkpoint rebuild for a site. Returns as soon as the rebuild is flagged
     * and scheduled; the build itself runs asynchronously. Idempotent: a site whose rebuild is
     * already queued is not queued again.
     *
     * @param siteId site identifier
     * @return {@code true} when newly queued, {@code false} when a rebuild was already pending
     * @throws RejectedExecutionException when the rebuild queue is full (the flag is cleared first)
     */
    public boolean requestRebuild(UUID siteId) {
        if (!syncStateService.requestRebuild(siteId)) {
            log.info("Checkpoint rebuild already queued, ignoring duplicate request: siteId={}", siteId);
            return false;
        }
        try {
            rebuildExecutor.execute(() -> runRebuild(siteId));
        } catch (RejectedExecutionException e) {
            // The flag committed before the submit; without this it would stick forever.
            syncStateService.clearRebuildRequested(siteId);
            throw e;
        }
        log.info("Checkpoint rebuild queued: siteId={}", siteId);
        return true;
    }

    /**
     * Re-drive rebuilds whose durable flag survived a restart while the queued task did not.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumePendingRebuilds() {
        List<UUID> pending = syncStateService.findSitesWithPendingRebuild();
        for (UUID siteId : pending) {
            log.info("Re-driving checkpoint rebuild orphaned by restart: siteId={}", siteId);
            try {
                rebuildExecutor.execute(() -> runRebuild(siteId));
            } catch (RejectedExecutionException e) {
                log.warn("Rebuild queue full during startup recovery — clearing flag: siteId={}", siteId, e);
                syncStateService.clearRebuildRequested(siteId);
            }
        }
    }

    private void runRebuild(UUID siteId) {
        try {
            checkpointService.buildCheckpoint(siteId, true);
            log.info("Forced checkpoint rebuild completed: siteId={}", siteId);
        } catch (Exception e) {
            log.error("Forced checkpoint rebuild failed: siteId={}", siteId, e);
        } finally {
            syncStateService.clearRebuildRequested(siteId);
        }
    }
}
