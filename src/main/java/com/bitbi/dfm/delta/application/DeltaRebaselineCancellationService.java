package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Takes back a pending full re-baseline request and reports whether the full snapshot was actually
 * averted (issue #84).
 *
 * <p>The {@code rebaseline_requested} flag alone cannot answer that. A FULL_SNAPSHOT session
 * consumes it only when it <em>commits</em> ({@link DeltaRebaselineService#reset} runs inside the
 * commit transaction so a dropped snapshot leaves the old baseline intact), and the session carries
 * its own re-baseline intent for its whole lifetime. So the flag stays raised for the hours a large
 * snapshot is uploading, and clearing it in that window changes nothing for the running session:
 * it still wipes the baseline when it commits.</p>
 *
 * <p>The open-session check closes that hole. The server cannot see an open session's mode, so an
 * ordinary delta session (or an abandoned batch awaiting the timeout sweeper) reports the same
 * {@link Outcome#SESSION_IN_PROGRESS} — the outcome means "may still be running", not "is".</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaRebaselineCancellationService {

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
        if (!syncStateService.cancelRebaseline(siteId)) {
            return Outcome.NOT_REQUESTED;
        }
        // Checked after clearing the flag: a session opened before the clear may be the snapshot
        // being called off, and clearing the flag never reaches it. Clearing is still right — once
        // that session ends or drops, GetSyncState must not order yet another full snapshot.
        return batchRepository.findActiveBySiteId(siteId).isPresent()
                ? Outcome.SESSION_IN_PROGRESS
                : Outcome.CANCELLED;
    }

    /**
     * Result of a cancellation, as reported to the caller.
     */
    public enum Outcome {

        /** The request was pending and no session is open: the full snapshot is called off. */
        CANCELLED("cancelled"),

        /**
         * The request was cleared, but the site has an ingestion session open. If that session is
         * the FULL_SNAPSHOT, it runs to completion and replaces the baseline regardless.
         */
        SESSION_IN_PROGRESS("session-in-progress"),

        /**
         * Nothing was pending — the snapshot already committed (which consumes the flag), another
         * operator got there first, or no request was ever made.
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
