package com.bitbi.dfm.delta.domain;

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
     * Save (insert or update) the sync state.
     *
     * @param state sync state entity
     * @return saved entity
     */
    SiteSyncState save(SiteSyncState state);
}
