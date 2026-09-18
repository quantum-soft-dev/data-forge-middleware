package com.bitbi.dfm.batch.domain;

/**
 * Insert/update/delete counts of one table across a whole Delta v2 session (issue #346).
 * <p>
 * Stored in {@code batches.table_stats} with the same JSON shape a changelog segment's
 * {@code stats} has ({@code {"inserts":…,"updates":…,"deletes":…}}), which is what lets V58
 * backfill it from the segments directly in SQL.
 * </p>
 *
 * @param inserts inserted rows
 * @param updates updated rows
 * @param deletes deleted rows
 * @author Data Forge Team
 * @version 1.0.0
 */
public record BatchTableStats(long inserts, long updates, long deletes) {

    /**
     * @param other the counts to add
     * @return the sum of both
     */
    public BatchTableStats plus(BatchTableStats other) {
        return new BatchTableStats(inserts + other.inserts, updates + other.updates, deletes + other.deletes);
    }
}
