package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
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
                        state.isRebaselineRequested()))
                .orElseGet(() -> new SyncStateView(0L, 0L, 0, false));
    }

    /**
     * Fetch the raw sync state entity for a site, if the client has ever synced. Used by the
     * REST presentation (B4) which needs the full row incl. timestamps and request flags.
     *
     * @param siteId site identifier
     * @return the sync state row, or empty when the client never connected
     */
    @Transactional(readOnly = true)
    public Optional<SiteSyncState> findSyncState(UUID siteId) {
        return repository.findBySiteId(siteId);
    }

    /**
     * Flag a site for a full re-baseline (creating the sync state row if absent): the next
     * {@code GetSyncState} answers NEED_REBASELINE; the flag is consumed when the client starts
     * its FULL_SNAPSHOT session ({@link SiteSyncState#resetForRebaseline}).
     *
     * @param siteId site identifier
     */
    @Transactional
    public void requestRebaseline(UUID siteId) {
        SiteSyncState state = repository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        state.requestRebaseline();
        repository.save(state);
    }

    /**
     * Take back a pending re-baseline request (issue #84), so {@code GetSyncState} answers PROCEED
     * again and the client resumes ordinary delta from its watermark. Only the flag is cleared —
     * watermark, checkpoints and segments are untouched. Idempotent: a site with no pending request
     * (or no sync state row at all, which already means PROCEED) is left alone.
     *
     * <p>Clearing the flag says nothing about a FULL_SNAPSHOT already in flight (it keeps its own
     * intent until it commits) — callers that report the outcome to a user go through
     * {@link DeltaRebaselineCancellationService} instead.</p>
     *
     * @param siteId site identifier
     * @return {@code true} when a pending request was cleared, {@code false} when none was pending
     */
    @Transactional
    public boolean cancelRebaseline(UUID siteId) {
        SiteSyncState state = repository.findBySiteId(siteId).orElse(null);
        if (state == null || !state.cancelRebaseline()) {
            return false;
        }
        repository.save(state);
        return true;
    }

    /**
     * Remember that {@code GetSyncState} answered NEED_REBASELINE for a site's pending request
     * (issue #84), so a later cancellation can tell "the client has not been told yet" from "the
     * client may already be preparing the snapshot". No-op once recorded, so the continuous
     * GetSyncState polling costs one write per request, not one per poll.
     *
     * @param siteId site identifier
     */
    @Transactional
    public void markRebaselineNotified(UUID siteId) {
        repository.findBySiteId(siteId)
                .filter(SiteSyncState::markRebaselineNotified)
                .ifPresent(repository::save);
    }

    /**
     * Flag a site for a forced out-of-schedule checkpoint rebuild (creating the sync state row
     * if absent); cleared via {@link #clearRebuildRequested} once the rebuild completes.
     * Idempotent: an already-flagged site is left untouched.
     *
     * @param siteId site identifier
     * @return {@code true} when newly flagged, {@code false} when a rebuild was already requested
     */
    @Transactional
    public boolean requestRebuild(UUID siteId) {
        SiteSyncState state = repository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        if (state.isRebuildRequested()) {
            return false;
        }
        state.requestRebuild();
        repository.save(state);
        return true;
    }

    /**
     * Sites whose forced-rebuild flag is set — startup recovery input (review r3).
     *
     * @return site identifiers with a pending rebuild request
     */
    @Transactional(readOnly = true)
    public List<UUID> findSitesWithPendingRebuild() {
        return repository.findSiteIdsWithRebuildRequested();
    }

    /**
     * Clear the forced-rebuild flag after the rebuild attempt completes. No-op when the site has
     * no sync state row.
     *
     * @param siteId site identifier
     */
    @Transactional
    public void clearRebuildRequested(UUID siteId) {
        repository.findBySiteId(siteId).ifPresent(state -> {
            state.clearRebuildRequested();
            repository.save(state);
        });
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
     * Record that a checkpoint up to {@code seq} has been materialized for a site. Monotonic: a
     * lower-or-equal {@code seq} (e.g. a stale or concurrent rebuild) is a no-op, so the checkpoint
     * pointer never regresses (which would re-fold already-checkpointed segments or orphan the frame).
     *
     * @param siteId site identifier
     * @param seq    sequence the checkpoint represents
     */
    @Transactional
    public void recordCheckpoint(UUID siteId, long seq) {
        SiteSyncState state = repository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        if (seq > state.getLastCheckpointSeq()) {
            state.recordCheckpoint(seq);
            repository.save(state);
        }
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
