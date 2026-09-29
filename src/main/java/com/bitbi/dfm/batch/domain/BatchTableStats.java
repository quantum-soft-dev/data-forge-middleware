package com.bitbi.dfm.batch.domain;

import java.io.Serializable;

/**
 * Insert/update/delete counts of one table across a whole Delta v2 session (issue #346).
 * <p>
 * Stored in {@code batches.table_stats} with the same JSON shape a changelog segment's
 * {@code stats} has ({@code {"inserts":…,"updates":…,"deletes":…}}), which is what lets V58
 * backfill it from the segments directly in SQL.
 * </p>
 * <p>
 * {@link Serializable} because it is the value type of that JSONB map, and hypersistence-utils 3.15
 * clones such an attribute by Java serialization — on load and on {@code em.remove}, whose deleted
 * state is a deep copy. Without it every JPA delete of a batch with stored totals failed: retention,
 * the admin batch delete and site deletion (issue #366; the same trap as {@code TableChangeStats}
 * in #302). The JSON shape is unchanged — Jackson writes the record by its components.
 * </p>
 *
 * @param inserts inserted rows
 * @param updates updated rows
 * @param deletes deleted rows
 * @author Data Forge Team
 * @version 1.0.0
 */
public record BatchTableStats(long inserts, long updates, long deletes) implements Serializable {

    /**
     * @param other the counts to add
     * @return the sum of both
     */
    public BatchTableStats plus(BatchTableStats other) {
        return new BatchTableStats(inserts + other.inserts, updates + other.updates, deletes + other.deletes);
    }
}
