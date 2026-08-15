package com.bitbi.dfm.delta.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Checkpoint} persistence (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface CheckpointRepository {

    Checkpoint save(Checkpoint checkpoint);

    Optional<Checkpoint> findBySiteIdAndTableName(UUID siteId, String tableName);

    List<Checkpoint> findBySiteId(UUID siteId);

    /**
     * Sites owed a rematerialize: those with at least one checkpoint row whose Parquet snapshot is
     * missing (issue #137).
     *
     * <p>The scheduled build walks the sites named by {@code changelog_segments}, but a site pruned
     * to nothing — {@code delta.retention.audit-window-segments=0}, or simply a table detached long
     * enough for its last segment to age out of the window — has no segment row left while still
     * owing a snapshot that {@code CheckpointService} can rebuild from the frame alone. This names
     * exactly those sites: having checkpoints is not enough, only having an unmaterialized one is,
     * so a fully materialized site is never woken up for nothing.</p>
     *
     * @return site identifiers with at least one detached checkpoint snapshot
     */
    List<UUID> findSiteIdsWithUnmaterializedCheckpoints();

    void deleteById(UUID id);

    /**
     * Delete every checkpoint of a site (issue #89 — history wipe).
     *
     * @param siteId site identifier
     * @return number of checkpoints deleted
     */
    int deleteBySiteId(UUID siteId);
}
