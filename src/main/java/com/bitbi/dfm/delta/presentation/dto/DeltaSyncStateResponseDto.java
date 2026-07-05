package com.bitbi.dfm.delta.presentation.dto;

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
 * @param rebaselineRequested whether a full re-baseline is pending (cleared when the client starts FULL_SNAPSHOT)
 * @param rebuildRequested    whether a forced checkpoint rebuild is queued (cleared when it completes)
 */
public record DeltaSyncStateResponseDto(
        long lastAppliedSeq,
        long lastCheckpointSeq,
        Instant lastCheckpointAt,
        int schemaVersion,
        Instant updatedAt,
        boolean rebaselineRequested,
        boolean rebuildRequested
) {

    /**
     * Convert the SiteSyncState entity to its REST projection.
     *
     * @param state the sync state entity
     * @return response DTO
     */
    public static DeltaSyncStateResponseDto fromEntity(SiteSyncState state) {
        return new DeltaSyncStateResponseDto(
                state.getLastAppliedSeq(),
                state.getLastCheckpointSeq(),
                state.getLastCheckpointAt() == null ? null : state.getLastCheckpointAt().toInstant(ZoneOffset.UTC),
                state.getSchemaVersion(),
                state.getUpdatedAt().toInstant(ZoneOffset.UTC),
                state.isRebaselineRequested(),
                state.isRebuildRequested()
        );
    }
}
