package com.bitbi.dfm.plugin.domain;

import java.util.List;

/**
 * How a Bit BI v2 consumer finds "the same row" of a table (issue #386, decision 6 of
 * {@code docs/cr-bitbi-parquet-plugin.md}).
 *
 * <p>Derived, never stored: a table whose {@code SubmitSchema} declares a non-empty
 * {@code primaryKey} is keyed by those columns, every other table by the client's
 * {@code _row_hash}. Every DBF table is the second kind, since DBF has no primary keys.</p>
 *
 * <p>This is what the consumer is told to key by; the server's own row identity is unchanged — it
 * keys a row by its full {@code key} (issue #369 declined keying by the hash).</p>
 */
public enum RowIdentity {

    /** Keyed by {@code _row_hash}: only {@code INSERT} and {@code DELETE} arrive. */
    ROW_HASH("row_hash"),

    /** Keyed by the declared primary key: {@code INSERT}, {@code UPDATE} and {@code DELETE}. */
    PRIMARY_KEY("primary_key");

    private final String wireValue;

    RowIdentity(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The identity a table's declared primary key implies.
     *
     * @param primaryKey the declared primary key columns; {@code null} or empty means none
     * @return {@link #PRIMARY_KEY} when at least one column is declared, {@link #ROW_HASH} otherwise
     */
    public static RowIdentity of(List<String> primaryKey) {
        return primaryKey == null || primaryKey.isEmpty() ? ROW_HASH : PRIMARY_KEY;
    }

    /**
     * The spelling on the wire of {@code GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/schema}.
     *
     * @return {@code row_hash} or {@code primary_key}
     */
    public String wireValue() {
        return wireValue;
    }
}
