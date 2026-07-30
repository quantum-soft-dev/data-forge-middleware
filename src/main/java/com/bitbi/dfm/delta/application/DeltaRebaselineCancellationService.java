package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.RebaselineCancellation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
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
 * <p><b>A live snapshot is left alone entirely.</b> The request is not merely un-cancellable then —
 * it is load-bearing: holding it until the snapshot commits is what re-arms a clean retry if the
 * snapshot dies part-way (033/#87). Clearing it would leave the site on the un-replaced baseline
 * with nothing pending, so the client would resume ordinary delta on top of the very divergence the
 * re-baseline was meant to fix.</p>
 *
 * <p>Liveness matters as much as the mode: {@code stageForResume} deliberately leaves a dropped
 * snapshot's batch {@code IN_PROGRESS} so a reconnect can re-attach, so without the batch-timeout
 * cutoff a dead session would answer "still uploading" for the best part of an hour.</p>
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
    private final int batchTimeoutMinutes;

    public DeltaRebaselineCancellationService(DeltaSyncStateService syncStateService,
                                              BatchRepository batchRepository,
                                              @Value("${batch.timeout.minutes:60}") int batchTimeoutMinutes) {
        this.syncStateService = syncStateService;
        this.batchRepository = batchRepository;
        this.batchTimeoutMinutes = batchTimeoutMinutes;
    }

    /**
     * Clear a pending re-baseline request. The watermark, checkpoints and segments are untouched, so
     * the client resumes ordinary delta from where it stopped. Idempotent. A snapshot that is
     * actually uploading is reported, not disturbed — see the class javadoc.
     *
     * @param siteId site identifier
     * @return what the cancellation achieved
     */
    @Transactional
    public Outcome cancel(UUID siteId) {
        Optional<Batch> session = liveSession(siteId);
        if (session.map(DeltaRebaselineCancellationService::isSnapshot).orElse(false)) {
            // Deliberately before (and instead of) clearing the flag: the running snapshot needs the
            // request to survive so a drop before its commit re-arms the retry.
            return Outcome.SNAPSHOT_IN_PROGRESS;
        }
        // A live session whose mode predates V47 (or was opened by the previous release mid-rollout)
        // may be that snapshot; the request is safe to clear, but the outcome must not claim success.
        boolean unknownSession = session.isPresent() && session.get().getSessionMode() == null;

        RebaselineCancellation cleared = syncStateService.cancelRebaseline(siteId);
        return switch (cleared) {
            case NOT_PENDING -> Outcome.NOT_REQUESTED;
            case CLEARED_AFTER_NOTICE -> Outcome.CLIENT_NOTIFIED;
            case CLEARED_BEFORE_NOTICE -> unknownSession ? Outcome.CLIENT_NOTIFIED : Outcome.CANCELLED;
        };
    }

    /**
     * Whether the site is uploading a FULL_SNAPSHOT right now — i.e. a full re-upload is running and
     * will replace the baseline when it commits. Surfaced on the sync-state projection so the UI can
     * keep showing it instead of relying on a one-shot message.
     *
     * @param siteId site identifier
     * @return {@code true} when a live FULL_SNAPSHOT session is in progress
     */
    @Transactional(readOnly = true)
    public boolean isSnapshotSessionOpen(UUID siteId) {
        return liveSession(siteId).map(DeltaRebaselineCancellationService::isSnapshot).orElse(false);
    }

    /**
     * The site's open session, unless it has been silent long enough for the timeout sweeper to
     * reap it — a batch left behind by a dropped session must not read as a running one.
     */
    private Optional<Batch> liveSession(UUID siteId) {
        return batchRepository.findActiveBySiteId(siteId)
                .filter(batch -> !batch.isExpired(batchTimeoutMinutes));
    }

    private static boolean isSnapshot(Batch batch) {
        return FULL_SNAPSHOT_MODE.equals(batch.getSessionMode());
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
         * replaces the baseline when it commits, so it cannot be called off — and the request is
         * left standing so a snapshot that dies part-way is retried.
         */
        SNAPSHOT_IN_PROGRESS("snapshot-in-progress"),

        /**
         * The request was cleared, but the client may already be acting on it: either
         * {@code GetSyncState} had answered NEED_REBASELINE, or the site has an open session whose
         * mode the server cannot see (a batch from before V47 or from the previous release).
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
