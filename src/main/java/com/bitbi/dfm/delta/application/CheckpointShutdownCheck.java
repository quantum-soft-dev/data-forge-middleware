package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;

import java.util.UUID;

/**
 * The checkpoint build's one question to the process lifecycle, shared by the path selection, the
 * frame producer and the snapshot materializer (issue #297).
 */
final class CheckpointShutdownCheck {

    private final ApplicationShutdownSignal shutdownSignal;

    CheckpointShutdownCheck(ApplicationShutdownSignal shutdownSignal) {
        this.shutdownSignal = shutdownSignal;
    }

    boolean isShuttingDown() {
        return shutdownSignal.isShuttingDown();
    }

    /**
     * End the build if the application has begun to close.
     *
     * <p>Thrown rather than returned so it escapes the per-table catch: a build ending with the
     * process must publish nothing, not skip one table and carry on to the next.</p>
     */
    void stopIfShuttingDown(UUID siteId) {
        if (shutdownSignal.isShuttingDown()) {
            throw new CheckpointService.BuildEndedByShutdownException(siteId, null, null);
        }
    }
}
