package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service exposing the per-site delta ingestion sync state (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSyncStateService {

    private final SiteSyncStateRepository repository;

    public DeltaSyncStateService(SiteSyncStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolve the current sync state for a site. Returns a zeroed view when the site has
     * never synced (the client should bootstrap with a FULL_SNAPSHOT session).
     *
     * @param siteId authenticated site identifier
     * @return current sync state view
     */
    @Transactional(readOnly = true)
    public SyncStateView getSyncState(UUID siteId) {
        return repository.findBySiteId(siteId)
                .map(state -> new SyncStateView(
                        state.getLastAppliedSeq(),
                        state.getLastCheckpointSeq(),
                        state.getSchemaVersion(),
                        false))
                .orElseGet(() -> new SyncStateView(0L, 0L, 0, false));
    }

    /**
     * Advance the applied watermark for a site to {@code seq} (creating the sync state row if
     * absent). Monotonic: a lower-or-equal {@code seq} is a no-op.
     *
     * @param siteId site identifier
     * @param seq    highest sequence now durably applied
     */
    @Transactional
    public void advanceWatermark(UUID siteId, long seq) {
        SiteSyncState state = repository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        if (seq > state.getLastAppliedSeq()) {
            state.advanceWatermark(seq);
            repository.save(state);
        }
    }

    /**
     * Record the schema version the server currently holds for a site (creating the sync state row if
     * absent), so {@code GetSyncState} and {@code SessionStart} validation reflect the submitted schema.
     *
     * @param siteId  site identifier
     * @param version current schema version
     */
    @Transactional
    public void recordSchemaVersion(UUID siteId, int version) {
        SiteSyncState state = repository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        state.recordSchemaVersion(version);
        repository.save(state);
    }

    /**
     * Record that a checkpoint up to {@code seq} has been materialized for a site.
     *
     * @param siteId site identifier
     * @param seq    sequence the checkpoint represents
     */
    @Transactional
    public void recordCheckpoint(UUID siteId, long seq) {
        SiteSyncState state = repository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        state.recordCheckpoint(seq);
        repository.save(state);
    }

    /**
     * Immutable view of a site's sync state.
     *
     * @param lastAppliedSeq    highest durably-applied change sequence
     * @param lastCheckpointSeq sequence of the latest materialized checkpoint
     * @param schemaVersion     schema version the server currently holds
     * @param needRebaseline    whether the client must re-baseline (full snapshot)
     */
    public record SyncStateView(long lastAppliedSeq, long lastCheckpointSeq, int schemaVersion, boolean needRebaseline) {
    }
}
