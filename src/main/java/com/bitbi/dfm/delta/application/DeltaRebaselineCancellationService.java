package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.RebaselineCancellation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Takes back a pending full re-baseline request and reports whether the full snapshot was actually
 * averted (issue #84).
 *
 * <p>Neither input answers that alone. The {@code rebaseline_requested} flag survives the whole
 * FULL_SNAPSHOT session — {@link DeltaRebaselineService#reset} runs inside the commit transaction so
 * a dropped snapshot leaves the old baseline intact — and the session carries its own re-baseline
 * intent, so clearing the flag mid-upload changes nothing for it. Conversely an open batch is just
 * as likely to be an ordinary delta session, which a cancellation does reach.</p>
 *
 * <p>So the outcome combines three facts: whether a request was pending, whether the client had
 * already been handed NEED_REBASELINE (V47's {@code rebaseline_notified_at}), and whether the site's
 * open session is a FULL_SNAPSHOT (V47's {@code batches.session_mode}). The flag is cleared in every
 * case — once a running snapshot ends or drops, the site must not be ordered to send another.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaRebaselineCancellationService {

    /** {@code SessionMode.FULL_SNAPSHOT} as recorded on the batch by the ingestion service. */
    private static final String FULL_SNAPSHOT_MODE = "FULL_SNAPSHOT";

    private final DeltaSyncStateService syncStateService;
    private final BatchRepository batchRepository;

    public DeltaRebaselineCancellationService(DeltaSyncStateService syncStateService,
                                              BatchRepository batchRepository) {
        this.syncStateService = syncStateService;
        this.batchRepository = batchRepository;
    }

    /**
     * Clear a pending re-baseline request. The watermark, checkpoints and segments are untouched,
     * so the client resumes ordinary delta from where it stopped. Idempotent.
     *
     * @param siteId site identifier
     * @return what the cancellation achieved
     */
    @Transactional
    public Outcome cancel(UUID siteId) {
        RebaselineCancellation cleared = syncStateService.cancelRebaseline(siteId);
        // Probed after clearing, and regardless of what was cleared: a snapshot opened before this
        // call is unreachable either way, and a second operator whose request was already taken
        // back by the first must still be told that the snapshot is running.
        if (isSnapshotSessionOpen(siteId)) {
            return Outcome.SNAPSHOT_IN_PROGRESS;
        }
        return switch (cleared) {
            case NOT_PENDING -> Outcome.NOT_REQUESTED;
            case CLEARED_AFTER_NOTICE -> Outcome.CLIENT_NOTIFIED;
            case CLEARED_BEFORE_NOTICE -> Outcome.CANCELLED;
        };
    }

    /**
     * Whether the site's open ingestion session (if any) is a FULL_SNAPSHOT — i.e. a full re-upload
     * is running right now and will replace the baseline when it commits. Surfaced on the sync-state
     * projection so the UI can keep showing it instead of relying on a one-shot message.
     *
     * @param siteId site identifier
     * @return {@code true} when a FULL_SNAPSHOT session is in progress
     */
    @Transactional(readOnly = true)
    public boolean isSnapshotSessionOpen(UUID siteId) {
        return batchRepository.findActiveBySiteId(siteId)
                .map(Batch::getSessionMode)
                .filter(FULL_SNAPSHOT_MODE::equals)
                .isPresent();
    }

    /**
     * Result of a cancellation, as reported to the caller.
     */
    public enum Outcome {

        /**
         * The request was pending, the client had not been told yet and no snapshot is running:
         * the full re-upload is called off.
         */
        CANCELLED("cancelled"),

        /**
         * A FULL_SNAPSHOT session is uploading right now. It keeps its own re-baseline intent and
         * replaces the baseline when it commits, so it cannot be called off.
         */
        SNAPSHOT_IN_PROGRESS("snapshot-in-progress"),

        /**
         * The request was cleared, but {@code GetSyncState} had already answered NEED_REBASELINE:
         * the client may open its snapshot session at any moment, and the server cannot tell
         * whether it will.
         */
        CLIENT_NOTIFIED("client-notified"),

        /**
         * Nothing was pending and no snapshot is running — the request was never made, already
         * committed, or another operator took it back first.
         */
        NOT_REQUESTED("not-requested");

        private final String status;

        Outcome(String status) {
            this.status = status;
        }

        /**
         * @return the wire status reported by the REST endpoints
         */
        public String status() {
            return status;
        }
    }
}
