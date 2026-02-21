package com.bitbi.dfm.plugin.domain;

import java.util.Map;

/**
 * A single change record parsed from a JSONL delta file.
 *
 * <p>Used for {@code POSTGRES_CDC} site type. Each line in the
 * {@code .jsonl} (or {@code .jsonl.gz}) file represents one row operation.</p>
 *
 * <p>Example lines:</p>
 * <pre>
 * {"op":"I","d":{"id":1,"name":"Alice","email":"a@ex.com"}}
 * {"op":"U","k":{"id":2},"d":{"name":"Bob Updated"}}
 * {"op":"D","k":{"id":3}}
 * </pre>
 *
 * @param op         operation code: {@code "I"} insert, {@code "U"} update, {@code "D"} delete
 * @param key        primary-key values identifying the row; present for U and D, {@code null} for I
 * @param data       column values to write; full row for I, changed columns only for U, {@code null} for D
 * @param lineNumber 1-based line number in the source JSONL file (for diagnostics)
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public record JsonlChangeRecord(
        String op,
        Map<String, Object> key,
        Map<String, Object> data,
        int lineNumber
) {
    /** Operation code constant for INSERT. */
    public static final String OP_INSERT = "I";

    /** Operation code constant for UPDATE. */
    public static final String OP_UPDATE = "U";

    /** Operation code constant for DELETE. */
    public static final String OP_DELETE = "D";
}
