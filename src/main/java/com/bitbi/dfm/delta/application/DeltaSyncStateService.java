package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.CheckpointBuildAbort;
import com.bitbi.dfm.delta.domain.CheckpointRebuildOutcome;
import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
                        state.isRebaselineRequested(),
                        state.getRebaselineNotifiedAt() != null,
                        state.getGeneration(),
                        state.getBaselineEpoch()))
                .orElseGet(() -> new SyncStateView(0L, 0L, 0, false, false, 0L, 0L));
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
     * {@code GetSyncState} answers NEED_REBASELINE; the flag is consumed when the client's
     * FULL_SNAPSHOT session <em>commits</em> ({@link SiteSyncState#resetForRebaseline}), so it stays
     * raised for the whole snapshot upload.
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
     * @return whether a request was cleared, and whether the client had already been told about it
     */
    @Transactional
    public RebaselineCancellation cancelRebaseline(UUID siteId) {
        SiteSyncState state = repository.findBySiteId(siteId).orElse(null);
        if (state == null) {
            return RebaselineCancellation.NOT_PENDING;
        }
        boolean notified = state.getRebaselineNotifiedAt() != null;
        if (!state.cancelRebaseline()) {
            return RebaselineCancellation.NOT_PENDING;
        }
        repository.save(state);
        return notified
                ? RebaselineCancellation.CLEARED_AFTER_NOTICE
                : RebaselineCancellation.CLEARED_BEFORE_NOTICE;
    }

    /**
     * What clearing the {@code rebaseline_requested} flag achieved (issue #84).
     */
    public enum RebaselineCancellation {

        /** No request was pending — nothing was cleared. */
        NOT_PENDING,

        /**
         * Cleared before {@code GetSyncState} ever answered NEED_REBASELINE: the client never
         * learned of the request, so it cannot act on it.
         */
        CLEARED_BEFORE_NOTICE,

        /**
         * Cleared after the client had already been told to re-baseline: it may already be
         * preparing or sending the snapshot, which this cancellation cannot reach.
         */
        CLEARED_AFTER_NOTICE
    }

    /**
     * Remember that {@code GetSyncState} answered NEED_REBASELINE for a site's pending request
     * (issue #84), so a later cancellation can tell "the client has not been told yet" from "the
     * client may already be preparing the snapshot". A single conditional statement: no entity
     * load, no lost update against a concurrent cancellation, and a no-op once recorded — so the
     * continuous GetSyncState polling costs one write per request, not one per poll.
     *
     * @param siteId site identifier
     */
    @Transactional
    public void markRebaselineNotified(UUID siteId) {
        repository.markRebaselineNotified(siteId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Take the site's pending-wipe flag (issue #89), but only if the site is still on
     * {@code epoch}. The caller that gets {@code true} owns the post-wipe follow-up work —
     * currently the Bit BI baseline recapture — and no other will.
     *
     * <p>The epoch is what keeps a <em>stale</em> caller from spending the flag (issue #142).
     * {@code CheckpointRecordedEvent} is published after the build's guarded pointer write has
     * committed, so a wipe can commit in the gap; without the predicate that event would consume the
     * new wipe's flag and recapture baselines from a {@code checkpoints} table the wipe has just
     * emptied, losing the automatic re-initialization for good.</p>
     *
     * @param siteId site identifier
     * @param epoch  the epoch the caller's checkpoint belongs to
     * @return {@code true} when this call consumed a pending wipe of that epoch
     */
    @Transactional
    public boolean consumeWipePending(UUID siteId, SiteEpoch epoch) {
        return repository.clearWipePending(siteId, epoch.generation(), epoch.baselineEpoch()) > 0;
    }

    /**
     * Flag a site for a forced out-of-schedule checkpoint rebuild (creating the sync state row
     * if absent); settled via {@link #recordRebuildOutcome} once the rebuild finishes.
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
     * Settle a finished forced rebuild: release the request flag and record how the attempt ended
     * (issue #186). No-op when the site has no sync state row.
     *
     * <p>Releasing the flag and recording the verdict are one write on purpose — before this,
     * releasing it was the whole of the report, and three of the four settling endings had run
     * nothing at all.</p>
     *
     * @param siteId  site identifier
     * @param outcome how the attempt ended
     * @param message operator-facing explanation (truncated by the entity), or null when the
     *                outcome needs none
     */
    @Transactional
    public void recordRebuildOutcome(UUID siteId, CheckpointRebuildOutcome outcome, String message) {
        repository.findBySiteId(siteId).ifPresent(state -> {
            state.recordRebuildOutcome(outcome, message);
            repository.save(state);
        });
    }

    /**
     * Record that a scheduled checkpoint build aborted before writing anything (issue #224).
     *
     * <p>No-op when the site has no sync-state row, and no-op when {@code lastCheckpointSeq} is
     * already past zero: the abort columns exist to tell "the first build is not due yet" from
     * "it already ran and produced nothing", and a site that has a checkpoint is past that. A
     * healthy build never calls this — it advances the pointer instead.</p>
     *
     * @param siteId  site identifier
     * @param abort   why the visit produced nothing
     * @param message operator-facing explanation (truncated by the entity), or null when the
     *                outcome needs none
     */
    @Transactional
    public void recordCheckpointBuildAbort(UUID siteId, CheckpointBuildAbort abort, String message) {
        repository.findBySiteId(siteId).ifPresent(state -> {
            if (state.getLastCheckpointSeq() != 0L) {
                return;
            }
            state.recordCheckpointBuildAbort(abort, message);
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
     * @param rebaselineNotified whether the pending request has already been handed to the client
     *                           (issue #84) — lets the caller skip a redundant stamping round-trip
     * @param generation        epoch of the site's server-side history (issue #89); a change tells
     *                          the client to drop its local journal and reset its seq counter
     * @param baselineEpoch     epoch of the site's server-side baseline (issue #142), moved by a wipe
     *                          <em>and</em> by a re-baseline; server-internal, never sent to the
     *                          client — {@code CheckpointEpochGuard} keys on it
     */
    public record SyncStateView(long lastAppliedSeq, long lastCheckpointSeq, int schemaVersion,
                                boolean needRebaseline, boolean rebaselineNotified,
                                long generation, long baselineEpoch) {

        /**
         * Both epochs as the pair {@code CheckpointEpochGuard} compares — neither subsumes the other
         * across a rolling deployment (issue #142).
         *
         * @return the site's epoch pair
         */
        public SiteEpoch epoch() {
            return new SiteEpoch(generation, baselineEpoch);
        }
    }
}
