package com.bitbi.dfm.site.domain;

import java.util.List;
import java.util.UUID;

/**
 * Removes a site's history — every row a site accumulates below the {@code sites} row itself, and
 * the objects those rows and the site's storage prefixes hold (issue #367).
 *
 * <p>A port: the site hard delete needs it, and the history it removes (changelog segments,
 * checkpoints, batch Parquet, plugin SQL, batches, uploads, error logs) belongs to aggregates the
 * {@code site} package must not depend on — {@code delta} already depends on {@code site}, so a
 * direct call from {@code site.application} would close a package cycle. The implementation lives
 * in {@code delta.application}, where the site history wipe uses the same code, so the two lists
 * cannot drift apart.</p>
 */
public interface SiteHistoryPurge {

    /**
     * Delete the site's history rows in foreign-key order, in the caller's transaction (an
     * implementation must refuse to run without one), collecting every S3 key before the row that
     * names it goes. Deletes no object: a rollback must leave every object in place. The site row,
     * its schema and its sync state are left to the caller.
     *
     * @param siteId the site whose history to delete
     * @return what was deleted, and the S3 keys the rows named
     */
    PurgedHistory purgeRows(UUID siteId);

    /**
     * Delete the objects of a site whose row has already been deleted and committed: the exact keys
     * {@link #purgeRows} collected, then everything under the site's storage prefixes. Must run
     * with no transaction active — it walks the site's whole object history over the network, and
     * an open transaction would hold its connection and row locks for all of it. Reports rather
     * than throws: the delete has already happened.
     *
     * @param siteId    the deleted site
     * @param exactKeys the keys {@link #purgeRows} returned
     */
    void deleteObjectsOfDeletedSite(UUID siteId, List<String> exactKeys);

    /**
     * What {@link #purgeRows} deleted, and the S3 keys the rows named.
     */
    record PurgedHistory(int deletedBatches, int deletedSegments, int deletedCheckpoints,
                         int deletedFiles, int deletedSqlGenerations, int deletedErrorLogs,
                         long deletedBytes, boolean baselineBatchDetached, List<String> s3Keys) {
    }
}
