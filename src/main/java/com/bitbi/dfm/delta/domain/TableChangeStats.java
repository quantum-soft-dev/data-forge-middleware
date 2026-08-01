package com.bitbi.dfm.delta.domain;

/**
 * Per-table insert/update/delete counts for one changelog segment (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record TableChangeStats(long inserts, long updates, long deletes) {
    public long total() {
        return inserts + updates + deletes;
    }
}
