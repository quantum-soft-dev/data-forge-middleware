package com.bitbi.dfm.site.domain;

/**
 * Type of site, determining how batch data is processed.
 *
 * <ul>
 *   <li>{@link #DBF} — Default. Full CSV snapshots each batch. Server diffs between batches.</li>
 *   <li>{@link #POSTGRES_CDC} — CDC mode. First batch = full CSV. Subsequent = JSONL deltas.</li>
 *   <li>{@link #MSSQL_CDC} — MS SQL CDC mode. Same as POSTGRES_CDC but source is MS SQL Server.</li>
 *   <li>{@link #DBF_CDC} — DBF CDC mode. Same as POSTGRES_CDC but source is DBF with CDC enabled.</li>
 * </ul>
 *
 * <p>Site type is immutable after creation — changing type would invalidate batch history.</p>
 *
 * @author Data Forge Team
 * @version 1.1.0
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
    POSTGRES_CDC,

    /**
     * MS SQL CDC site type. Same CDC flow as POSTGRES_CDC.
     * First batch = full CSV snapshot (baseline). Subsequent = JSONL deltas.
     * MSSQL-specific type aliases (nvarchar, datetime2, etc.) normalized at schema submission.
     */
    MSSQL_CDC,

    /**
     * DBF CDC site type. Same CDC flow as POSTGRES_CDC.
     * First batch = full CSV snapshot (baseline). Subsequent = JSONL deltas.
     * Enables delta mode for DBF data sources.
     */
    DBF_CDC;

    /**
     * Check if this site type uses CDC (Change Data Capture) mode.
     * CDC sites use CSV for baseline and JSONL for subsequent delta uploads.
     *
     * @return true if this is a CDC site type (POSTGRES_CDC, MSSQL_CDC, or DBF_CDC)
     */
    public boolean isCdc() {
        return this == POSTGRES_CDC || this == MSSQL_CDC || this == DBF_CDC;
    }
}
