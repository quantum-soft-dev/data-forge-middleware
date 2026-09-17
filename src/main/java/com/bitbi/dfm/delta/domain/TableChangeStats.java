package com.bitbi.dfm.delta.domain;

import java.io.Serializable;

/**
 * Per-table insert/update/delete counts for one changelog segment (Delta Client v2 — 022).
 * <p>
 * {@link Serializable} because it is the value type of the {@code changelog_segments.stats} JSONB map,
 * and hypersistence-utils 3.15 (Hibernate 7.4, issue #302) snapshots such an attribute for dirty
 * checking by Java serialization, refusing a non-serializable value instead of falling back to a JSON
 * copy as 3.9 did — every load of a segment with stats failed without it.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record TableChangeStats(long inserts, long updates, long deletes) implements Serializable {
    public long total() {
        return inserts + updates + deletes;
    }
}
