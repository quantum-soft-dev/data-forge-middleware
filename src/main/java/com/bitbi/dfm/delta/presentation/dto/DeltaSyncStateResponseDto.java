package com.bitbi.dfm.delta.presentation.dto;

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
        Instant nextCheckpointBuildAt
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
        return build(state, snapshotInProgress, state.getLastRebuildMessage(), nextCheckpointBuildAt);
    }

    /**
     * Convert the SiteSyncState entity to the <b>owner</b> REST projection.
     *
     * <p>Identical but for {@code lastRebuildMessage}, which is withheld. For a {@code FAILED}
     * verdict that string is the exception's own text — a {@code PSQLException} naming a constraint
     * or a column, an S3 error naming the bucket and endpoint — and this endpoint is the one place
     * a tenant user could read it. The account owner cannot request a rebuild in the first place
     * (the route is ROLE_ADMIN), so the outcome and its time are the whole of what the projection
     * owes them; the same rule keeps storage keys and claim tokens off the segment and artifact
     * projections.</p>
     *
     * <p>{@code nextCheckpointBuildAt} is <em>not</em> withheld: it is the deployment's schedule,
     * says nothing about the failure of anything, and the owner is precisely the user staring at a
     * site whose first checkpoint has not been built yet.</p>
     *
     * @param state                 the sync state entity
     * @param snapshotInProgress    whether the site's open session is a FULL_SNAPSHOT
     * @param nextCheckpointBuildAt when the scheduled checkpoint build next runs, or null
     * @return response DTO with the rebuild diagnosis withheld
     */
    public static DeltaSyncStateResponseDto forOwner(SiteSyncState state, boolean snapshotInProgress,
                                                     Instant nextCheckpointBuildAt) {
        return build(state, snapshotInProgress, null, nextCheckpointBuildAt);
    }

    private static DeltaSyncStateResponseDto build(SiteSyncState state, boolean snapshotInProgress,
                                                   String lastRebuildMessage,
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
                nextCheckpointBuildAt
        );
    }
}
