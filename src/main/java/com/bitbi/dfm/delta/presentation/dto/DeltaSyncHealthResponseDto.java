package com.bitbi.dfm.delta.presentation.dto;

import com.bitbi.dfm.delta.application.DeltaSyncHealthService.SiteHealth;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Bulk sync-health entry for one Delta v2 site (feature 023 — Delta Sync UI, B10).
 *
 * <p>Raw inputs for the site-list health badge; the client derives lag
 * ({@code lastAppliedSeq - lastCheckpointSeq}), severity and staleness. Sequence fields and
 * {@code updatedAt} are null when the client never connected ({@code hasSyncState=false} →
 * "No sync yet" badge).</p>
 *
 * @param siteId            site identifier
 * @param hasSyncState      whether the Delta client has ever connected
 * @param lastAppliedSeq    highest durably-applied change sequence (null without sync state)
 * @param lastCheckpointSeq latest materialized checkpoint sequence (null without sync state)
 * @param updatedAt         last sync-state update (null without sync state)
 */
public record DeltaSyncHealthResponseDto(
        UUID siteId,
        boolean hasSyncState,
        Long lastAppliedSeq,
        Long lastCheckpointSeq,
        Instant updatedAt
) {

    /**
     * Convert the application-layer health entry to its REST projection.
     *
     * @param health per-site health entry
     * @return response DTO
     */
    public static DeltaSyncHealthResponseDto of(SiteHealth health) {
        if (health.state() == null) {
            return new DeltaSyncHealthResponseDto(health.siteId(), false, null, null, null);
        }
        return new DeltaSyncHealthResponseDto(
                health.siteId(),
                true,
                health.state().getLastAppliedSeq(),
                health.state().getLastCheckpointSeq(),
                health.state().getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
