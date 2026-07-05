package com.bitbi.dfm.delta.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Forced out-of-schedule checkpoint rebuild (feature 023 — Delta Sync UI, B7).
 *
 * <p>Admin-triggered alternative to the nightly {@link CheckpointScheduler}: sets the persistent
 * {@code rebuild_requested} flag (surfaced in the UI as "Rebuild queued"), runs
 * {@link CheckpointService#buildCheckpoint} on a dedicated single-thread executor so forced
 * rebuilds serialize, and always clears the flag when the attempt finishes. The checkpoint
 * pointer is monotonic ({@link DeltaSyncStateService#recordCheckpoint}), so a collision with a
 * concurrent scheduled build cannot regress state.</p>
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
     * and scheduled; the build itself runs asynchronously.
     *
     * @param siteId site identifier
     */
    public void requestRebuild(UUID siteId) {
        syncStateService.requestRebuild(siteId);
        rebuildExecutor.execute(() -> runRebuild(siteId));
        log.info("Checkpoint rebuild queued: siteId={}", siteId);
    }

    private void runRebuild(UUID siteId) {
        try {
            checkpointService.buildCheckpoint(siteId);
            log.info("Forced checkpoint rebuild completed: siteId={}", siteId);
        } catch (Exception e) {
            log.error("Forced checkpoint rebuild failed: siteId={}", siteId, e);
        } finally {
            syncStateService.clearRebuildRequested(siteId);
        }
    }
}
