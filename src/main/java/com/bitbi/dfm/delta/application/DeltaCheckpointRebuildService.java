package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
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
 * {@link CheckpointService#rebuildFromFrame} on a dedicated single-thread executor so
 * forced rebuilds serialize, and always clears the flag when the attempt finishes. An idle
 * site is rematerialized from the existing frame even when there are no new segments
 * (issue #128). The checkpoint pointer is monotonic
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
    private final ApplicationShutdownSignal shutdownSignal;
    private final Executor rebuildExecutor;

    public DeltaCheckpointRebuildService(DeltaSyncStateService syncStateService,
                                         CheckpointService checkpointService,
                                         ApplicationShutdownSignal shutdownSignal,
                                         @Qualifier("deltaRebuildExecutor") Executor rebuildExecutor) {
        this.syncStateService = syncStateService;
        this.checkpointService = checkpointService;
        this.shutdownSignal = shutdownSignal;
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

    /**
     * Run one forced rebuild and settle its durable flag.
     *
     * <p>A build cut short because the application is shutting down is <b>neither</b> a success nor
     * a failure (issues #149, #162): {@code CheckpointService} deliberately publishes nothing in
     * that case and returns an empty fold, which here would otherwise read as "completed" — the log
     * would say so and the {@code finally} would spend the very flag {@link #resumePendingRebuilds}
     * exists to re-drive, so an operator's click during a rollout would vanish without trace. The
     * scheduled tick can afford #162's "the next process redoes it" because the nightly work list
     * finds the site again; this path has only the flag. So the flag is left set, and the new pod
     * picks the rebuild up at startup.</p>
     *
     * <p>The check is deliberately after the call rather than only inside it: a shutdown starting
     * just as a rebuild finished leaves the flag set and costs one redundant rebuild after the
     * restart, which is the harmless direction to be wrong in.</p>
     *
     * <p>An S3 read denial on the seed frame is the second such outcome (issue #157) and is handled
     * the same way. {@code CheckpointService} throws rather than returning an empty fold precisely
     * so this path can tell it apart from a finished build — otherwise a rebuild that never ran
     * would log "completed" and spend the flag, on the very action the {@code history_gone} message
     * names as the recovery.</p>
     */
    private void runRebuild(UUID siteId) {
        boolean keepFlagForARetry = false;
        try {
            checkpointService.rebuildFromFrame(siteId);
            keepFlagForARetry = shutdownSignal.isShuttingDown();
            if (!keepFlagForARetry) {
                log.info("Forced checkpoint rebuild completed: siteId={}", siteId);
            } else {
                logShutdown(siteId);
            }
        } catch (CheckpointService.FramePresenceUnknownException e) {
            keepFlagForARetry = true;
            log.warn("Forced checkpoint rebuild for site {} did not run: {}. Leaving "
                    + "rebuild_requested set, so the request survives the incident and is "
                    + "re-driven at the next start instead of being reported as done",
                    siteId, e.getMessage());
        } catch (Exception e) {
            keepFlagForARetry = shutdownSignal.isShuttingDown();
            if (!keepFlagForARetry) {
                log.error("Forced checkpoint rebuild failed: siteId={}", siteId, e);
            } else {
                logShutdown(siteId);
            }
        } finally {
            if (!keepFlagForARetry) {
                syncStateService.clearRebuildRequested(siteId);
            }
        }
    }

    private static void logShutdown(UUID siteId) {
        log.info("Forced checkpoint rebuild for site {} did not run to completion: the "
                + "application is shutting down. Leaving rebuild_requested set so the "
                + "next process re-drives it at startup", siteId);
    }
}
