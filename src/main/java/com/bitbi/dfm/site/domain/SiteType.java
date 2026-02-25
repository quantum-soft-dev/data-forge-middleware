package com.bitbi.dfm.site.domain;

/**
 * Type of site, determining how batch data is processed.
 *
 * <ul>
 *   <li>{@link #DBF} — Default. Full CSV snapshots each batch. Server diffs between batches.</li>
 *   <li>{@link #POSTGRES_CDC} — CDC mode. First batch = full CSV. Subsequent = JSONL deltas.</li>
 * </ul>
 *
 * <p>Site type is immutable after creation — changing type would invalidate batch history.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum SiteType {

    /**
     * Default site type. Full CSV snapshots each batch.
     * Server generates SQL deltas by comparing consecutive batches via Myers diff algorithm.
     */
    DBF,

    /**
     * Postgres CDC site type. First batch = full CSV snapshot (baseline).
     * Subsequent batches = compact JSONL delta files (.jsonl.gz).
     * Server converts JSONL deltas directly to SQL using PK from stored schema.
     */
    POSTGRES_CDC
}
