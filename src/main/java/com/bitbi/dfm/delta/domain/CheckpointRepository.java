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
     * missing and which has attempts left (issues #137, #149).
     *
     * <p>The scheduled build walks the sites named by {@code changelog_segments}, but a site pruned
     * to nothing — {@code delta.retention.audit-window-segments=0}, or simply a table detached long
     * enough for its last segment to age out of the window — has no segment row left while still
     * owing a snapshot that {@code CheckpointService} can rebuild from the frame alone. This names
     * exactly those sites: having checkpoints is not enough, only having an unmaterialized one is,
     * so a fully materialized site is never woken up for nothing.</p>
     *
     * <p>Nor is being unmaterialized enough on its own. A row that has spent {@code maxAttempts}
     * attempts without producing a snapshot is not going to produce one tonight either — it drops
     * out of this list, and with it the nightly frame download and whole-site fold that discovering
     * as much used to cost. A site with new segments is still visited through the segment list, and
     * an incremental build there writes every table in its fold regardless of this counter.</p>
     *
     * @param maxAttempts the configured ceiling ({@code delta.checkpoint.max-materialize-attempts})
     * @return site identifiers with at least one still-retryable detached checkpoint snapshot
     */
    List<UUID> findSiteIdsWithUnmaterializedCheckpoints(int maxAttempts);

    /**
     * How many checkpoint rows have given up materializing (issue #149).
     *
     * <p>Backs {@code delta.checkpoint.tables.given-up}. Giving up stops a nightly alarm, so it has
     * to raise a standing one in its place — otherwise the fix for "retried forever" would be
     * "silent forever".</p>
     *
     * @param maxAttempts the configured ceiling ({@code delta.checkpoint.max-materialize-attempts})
     * @return number of rows with no snapshot and no attempts left
     */
    long countGivenUpMaterializing(int maxAttempts);

    void deleteById(UUID id);

    /**
     * Delete every checkpoint of a site (issue #89 — history wipe).
     *
     * @param siteId site identifier
     * @return number of checkpoints deleted
     */
    int deleteBySiteId(UUID siteId);
}
