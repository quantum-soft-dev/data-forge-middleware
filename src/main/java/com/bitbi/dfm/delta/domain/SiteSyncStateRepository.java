package com.bitbi.dfm.delta.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for {@link SiteSyncState} persistence (Delta Client v2 — feature 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface SiteSyncStateRepository {

    /**
     * Find the sync state for a site.
     *
     * @param siteId site identifier
     * @return optional sync state
     */
    Optional<SiteSyncState> findBySiteId(UUID siteId);

    /**
     * Load the sync state for a site under a pessimistic write lock — the per-site mutex a history
     * wipe holds for its whole transaction (issue #89), so concurrent wipes and session commits
     * serialize on the one row every ingestion path already touches.
     *
     * @param siteId site identifier
     * @return the locked sync state, or empty when the site has never synced
     */
    Optional<SiteSyncState> findBySiteIdForUpdate(UUID siteId);

    /**
     * Bulk-fetch sync states for a set of sites in one query (Delta Sync UI, B10 —
     * site-list health badge; avoids one query per site under 30 s polling).
     *
     * @param siteIds site identifiers
     * @return sync states of the sites that have one
     */
    List<SiteSyncState> findBySiteIdIn(Collection<UUID> siteIds);

    /**
     * Record that {@code GetSyncState} has answered NEED_REBASELINE for a site's pending request
     * (issue #84), as one conditional statement rather than a load-modify-save.
     * <p>
     * {@link SiteSyncState} is {@code @DynamicUpdate} with no {@code @Version}, so a read-then-write
     * stamp can land after a concurrent cancellation has cleared the request and leave the row
     * claiming the client was told about a request that no longer exists. The predicate makes the
     * write self-cancelling instead, and self-idempotent: 0 rows when already stamped.
     * </p>
     *
     * @param siteId site identifier
     * @param now    stamp to record
     * @return number of rows stamped (0 when no request is pending or it was already recorded)
     */
    int markRebaselineNotified(UUID siteId, java.time.LocalDateTime now);

    /**
     * Take a site's pending-wipe flag as one conditional statement (issue #89), so the Bit BI
     * baseline recapture runs exactly once per wipe however many checkpoint builds race for it.
     * <p>
     * The same reasoning as {@link #markRebaselineNotified}: {@link SiteSyncState} is
     * {@code @DynamicUpdate} with no {@code @Version}, so a read-then-write clear could be lost
     * against a concurrent write, and two builds could both believe they consumed the flag.
     * </p>
     *
     * @param siteId site identifier
     * @return 1 when this call consumed a pending wipe, 0 when none was pending
     */
    int clearWipePending(UUID siteId);

    /**
     * Save (insert or update) the sync state.
     *
     * @param state sync state entity
     * @return saved entity
     */
    SiteSyncState save(SiteSyncState state);

    /**
     * Sites whose forced-rebuild flag is set (review r3): consumed by the startup recovery that
     * re-drives rebuilds orphaned by a restart between flag-commit and task execution.
     *
     * @return site identifiers with {@code rebuild_requested = true}
     */
    List<UUID> findSiteIdsWithRebuildRequested();
}
