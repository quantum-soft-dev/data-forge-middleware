package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.domain.CheckpointBuildAbort;
import com.bitbi.dfm.delta.domain.CheckpointRebuildOutcome;
import com.bitbi.dfm.delta.domain.SiteSyncState;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Response DTO for a site's delta sync state (feature 023 — Delta Sync UI, B4).
 *
 * @param lastAppliedSeq      highest durably-applied change sequence
 * @param lastCheckpointSeq   sequence of the latest materialized checkpoint
 * @param lastCheckpointAt    when the latest checkpoint was materialized (null before the first one)
 * @param schemaVersion       schema version the server currently holds
 * @param updatedAt           last sync-state update (drives the "stalled" indicator)
 * @param rebaselineRequested whether a full re-baseline is pending (consumed when the client's
 *                            FULL_SNAPSHOT session commits, not when it starts)
 * @param rebuildRequested    whether a forced checkpoint rebuild is queued (cleared when it completes)
 * @param snapshotInProgress  whether a FULL_SNAPSHOT session is uploading right now (issue #84)
 * @param lastRebuildOutcome  how the most recent <em>finished</em> forced rebuild ended
 *                            (issue #186), or null when the site has never had one. While
 *                            {@code rebuildRequested} is true this describes the <em>previous</em>
 *                            attempt, not the queued one — which is also why an ending that keeps
 *                            the flag (the process shutting down) writes nothing here
 * @param lastRebuildOutcomeAt when that outcome was recorded
 * @param lastRebuildMessage  explanation of the outcome, <b>admin projection only</b>; null for a
 *                            rebuild that completed, which has nothing to explain, and null for the
 *                            owner, who gets the outcome and its time but not the diagnosis
 * @param nextCheckpointBuildAt when the scheduled checkpoint build next runs (issue #213), or null
 *                            when the schedule names no occurrence at all. Checkpoints are produced
 *                            by that one cron and by an operator-forced rebuild, so a site with
 *                            {@code lastCheckpointSeq == 0} is not behind — it is waiting for this
 *                            moment, and a surface without it can only render the wait as a backlog
 * @param lastCheckpointBuildAbort why the last scheduled visit of a site that still has no
 *                            checkpoint produced nothing (issue #224), or null when none has
 *                            aborted on record. Distinguishes "not due yet" from "already tried
 *                            and failed"; a healthy build does not write this
 * @param lastCheckpointBuildAbortAt when that abort was recorded
 * @param lastCheckpointBuildMessage explanation of the abort, <b>admin projection only</b>; the
 *                            owner gets the reason and its time, the same split as
 *                            {@code lastRebuildMessage}
 */
public record DeltaSyncStateResponseDto(
        long lastAppliedSeq,
        long lastCheckpointSeq,
        Instant lastCheckpointAt,
        int schemaVersion,
        Instant updatedAt,
        boolean rebaselineRequested,
        boolean rebuildRequested,
        boolean snapshotInProgress,
        CheckpointRebuildOutcome lastRebuildOutcome,
        Instant lastRebuildOutcomeAt,
        String lastRebuildMessage,
        Instant nextCheckpointBuildAt,
        CheckpointBuildAbort lastCheckpointBuildAbort,
        Instant lastCheckpointBuildAbortAt,
        String lastCheckpointBuildMessage
) {

    /**
     * Convert the SiteSyncState entity to the <b>admin</b> REST projection, diagnosis included.
     *
     * @param state                 the sync state entity
     * @param snapshotInProgress    whether the site's open session is a FULL_SNAPSHOT — it outlives
     *                              the request flag (a snapshot consumes that only at commit), so
     *                              the UI needs it to keep showing that a full re-upload is under way
     * @param nextCheckpointBuildAt when the scheduled checkpoint build next runs, or null
     * @return response DTO
     */
    public static DeltaSyncStateResponseDto forAdmin(SiteSyncState state, boolean snapshotInProgress,
                                                     Instant nextCheckpointBuildAt) {
        return build(state, snapshotInProgress, state.getLastRebuildMessage(),
                state.getLastCheckpointBuildMessage(), nextCheckpointBuildAt);
    }

    /**
     * Convert the SiteSyncState entity to the <b>owner</b> REST projection.
     *
     * <p>Identical but for {@code lastRebuildMessage} and {@code lastCheckpointBuildMessage}, which
     * are withheld. For a {@code FAILED} verdict those strings are the exception's own text — a
     * {@code PSQLException} naming a constraint or a column, an S3 error naming the bucket and
     * endpoint — and this endpoint is the one place a tenant user could read it. The account owner
     * cannot request a rebuild in the first place (the route is ROLE_ADMIN), so the outcome and its
     * time are the whole of what the projection owes them for a forced rebuild; the same rule keeps
     * storage keys and claim tokens off the segment and artifact projections.</p>
     *
     * <p>{@code nextCheckpointBuildAt} and {@code lastCheckpointBuildAbort} are <em>not</em>
     * withheld: the schedule says nothing about a failure, and the abort reason is exactly what
     * lets the owner tell "the first build is not due yet" from "it already ran and produced
     * nothing" (issue #224). The diagnosis string stays admin-only, the same split as
     * {@code lastRebuildMessage}.</p>
     *
     * @param state                 the sync state entity
     * @param snapshotInProgress    whether the site's open session is a FULL_SNAPSHOT
     * @param nextCheckpointBuildAt when the scheduled checkpoint build next runs, or null
     * @return response DTO with the rebuild diagnosis withheld
     */
    public static DeltaSyncStateResponseDto forOwner(SiteSyncState state, boolean snapshotInProgress,
                                                     Instant nextCheckpointBuildAt) {
        return build(state, snapshotInProgress, null, null, nextCheckpointBuildAt);
    }

    private static DeltaSyncStateResponseDto build(SiteSyncState state, boolean snapshotInProgress,
                                                   String lastRebuildMessage,
                                                   String lastCheckpointBuildMessage,
                                                   Instant nextCheckpointBuildAt) {
        return new DeltaSyncStateResponseDto(
                state.getLastAppliedSeq(),
                state.getLastCheckpointSeq(),
                state.getLastCheckpointAt() == null ? null : state.getLastCheckpointAt().toInstant(ZoneOffset.UTC),
                state.getSchemaVersion(),
                state.getUpdatedAt().toInstant(ZoneOffset.UTC),
                state.isRebaselineRequested(),
                state.isRebuildRequested(),
                snapshotInProgress,
                state.getLastRebuildOutcome(),
                state.getLastRebuildOutcomeAt() == null
                        ? null : state.getLastRebuildOutcomeAt().toInstant(ZoneOffset.UTC),
                lastRebuildMessage,
                nextCheckpointBuildAt,
                state.getLastCheckpointBuildAbort(),
                state.getLastCheckpointBuildAbortAt() == null
                        ? null : state.getLastCheckpointBuildAbortAt().toInstant(ZoneOffset.UTC),
                lastCheckpointBuildMessage
        );
    }
}
