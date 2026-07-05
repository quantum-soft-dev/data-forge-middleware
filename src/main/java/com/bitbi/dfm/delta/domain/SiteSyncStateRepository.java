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
     * Bulk-fetch sync states for a set of sites in one query (Delta Sync UI, B10 —
     * site-list health badge; avoids one query per site under 30 s polling).
     *
     * @param siteIds site identifiers
     * @return sync states of the sites that have one
     */
    List<SiteSyncState> findBySiteIdIn(Collection<UUID> siteIds);

    /**
     * Save (insert or update) the sync state.
     *
     * @param state sync state entity
     * @return saved entity
     */
    SiteSyncState save(SiteSyncState state);
}
