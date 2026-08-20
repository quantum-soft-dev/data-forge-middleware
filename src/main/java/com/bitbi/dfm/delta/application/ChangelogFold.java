package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds a changelog into current per-key row state (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Starting from a checkpoint state, applies records in order: INSERT replaces the row by key,
 * UPDATE merges the changed columns, DELETE removes the row. Each surviving row keeps both its
 * structured {@code key} and {@code data} (typed {@link Value}s) so the state can be re-emitted as an
 * all-INSERT checkpoint frame (see {@link CheckpointFrame}) — the basis for checkpoints, CSV
 * reconstruction, and re-baseline.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ChangelogFold {

    private ChangelogFold() {
    }

    /**
     * A folded row: its identifying {@code key} and current {@code data}, both typed.
     *
     * @param key  the change records' key columns (drives row identity)
     * @param data the row's current column values
     */
    public record FoldedRow(Map<String, Value> key, Map<String, Value> data) {
    }

    /**
     * Apply {@code records} on top of {@code initial} (typically a checkpoint state) — a
     * <strong>test convenience</strong> since issue #152, and deliberately nothing more.
     *
     * <p>No production caller remains, and new code belongs on {@link #apply}. This shape is the one
     * the ticket removed: it copies {@code initial}, so both states exist at once and each of them
     * is a whole site, and it takes the records as a materialized {@code List}, which is a second
     * whole site again. The checkpoint build folds record by record into a state it owns instead.
     * Kept because the fold's semantics are most readable stated as a function of a starting state
     * and a record list, which is how the tests assert them.</p>
     *
     * @param initial starting state (not mutated)
     * @param records changelog records in sequence order
     * @return resulting state: table → row-identity → folded row
     */
    public static Map<String, Map<String, FoldedRow>> fold(
            Map<String, Map<String, FoldedRow>> initial, List<ChangeRecord> records) {

        Map<String, Map<String, FoldedRow>> state = deepCopy(initial);
        for (ChangeRecord record : records) {
            apply(state, record);
        }
        return state;
    }

    /**
     * Apply one record to {@code state} <b>in place</b>, and report what it did to the state's size.
     *
     * <p>The record is not retained: its key and data maps are copied into the row, so a caller
     * streaming a frame or a segment can drop each record as soon as this returns.</p>
     *
     * @param state  the fold so far — mutated
     * @param record the next changelog record, in sequence order
     * @return the change in the fold's estimated retained heap, in bytes: positive for a row that
     *         grew or arrived, negative for one that shrank or was deleted, zero when nothing about
     *         the state's size changed. See {@link #estimatedRetainedBytes} for what "estimated"
     *         is worth; summing these is how a build knows how big its fold has become
     *         (issue #152) without walking it. The cost is proportional to the width of the row
     *         this record touches — never to the size of the fold — and no wider than the map copy
     *         the fold is doing for that record anyway.
     */
    public static long apply(Map<String, Map<String, FoldedRow>> state, ChangeRecord record) {
        Map<String, FoldedRow> table = state.computeIfAbsent(record.getTable(), k -> new LinkedHashMap<>());
        String identity = identity(record.getKeyMap());
        switch (record.getOp()) {
            case INSERT -> {
                FoldedRow row = new FoldedRow(
                        new LinkedHashMap<>(record.getKeyMap()),
                        new LinkedHashMap<>(record.getDataMap()));
                FoldedRow replaced = table.put(identity, row);
                // The replaced row is weighed only when there was one: on a first INSERT — every
                // record of a seed frame — this is a single pass over the new row.
                return estimatedRetainedBytes(identity, row)
                        - (replaced == null ? 0L : estimatedRetainedBytes(identity, replaced));
            }
            case UPDATE -> {
                FoldedRow existing = table.get(identity);
                // Seed a brand-new row (no prior INSERT in state) with its key columns so the
                // materialized row never loses its primary key; an existing row already carries
                // its key columns in data from the original INSERT.
                Map<String, Value> mergedData = existing != null
                        ? new LinkedHashMap<>(existing.data())
                        : new LinkedHashMap<>(record.getKeyMap());
                mergedData.putAll(record.getDataMap());
                Map<String, Value> key = existing != null ? existing.key() : new LinkedHashMap<>(record.getKeyMap());
                FoldedRow row = new FoldedRow(key, mergedData);
                table.put(identity, row);
                if (existing == null) {
                    return estimatedRetainedBytes(identity, row);
                }
                // The row was already there and keeps the *same* key map object, so identity, row
                // and key all cancel: only the data map can have changed. Weighing the whole row
                // twice here would double the accounting's cost on the commonest record of all.
                return mapBytes(mergedData) - mapBytes(existing.data());
            }
            case DELETE -> {
                FoldedRow removed = table.remove(identity);
                return removed == null ? 0L : -estimatedRetainedBytes(identity, removed);
            }
            default -> {
                return 0L;
            }
        }
    }

    /**
     * A coarse estimate of how much heap one folded row occupies.
     *
     * <p><b>Estimated, and deliberately so.</b> The exact answer needs an object-graph walk per row,
     * which would cost more than the fold; this is arithmetic over the same things that make a row
     * big — its columns, their names and their values — using flat per-object costs for a 64-bit JVM
     * with compressed ordinary object pointers. It is within a small factor of the truth rather than
     * exact, which is what a budget expressed as a fraction of the heap needs it to be. It is
     * <em>not</em> the row's serialized size: the fold keeps a Java object graph, and for a narrow
     * row that graph is an order of magnitude larger than its protobuf encoding, so a wire-byte
     * budget could not be compared against {@code -Xmx} at all.</p>
     *
     * <p>Counted: the row's identity string (the map key, built per row and retained with it), both
     * of its maps entry by entry, each column name, and each value — a string, decimal or bytes
     * value carries its own payload, while an int, double, boolean or NULL lives inside the wrapper.
     * The key columns are counted twice on purpose: {@code key} and {@code data} each hold them.</p>
     *
     * <p><b>Where it under-counts</b>, so a deployment that matches can lower its budget rather than
     * be surprised: a character costs one byte here, which is what compact strings give for Latin-1
     * text — string data outside it (Cyrillic, CJK) is held as UTF-16 and costs twice that. The
     * per-table maps are not counted either, but there are tens of those against millions of rows.
     * Everything else errs the other way: the per-object costs below are rounded up.</p>
     *
     * @param identity the row's identity string, as used for the map key
     * @param row      the folded row
     * @return estimated retained bytes
     */
    static long estimatedRetainedBytes(String identity, FoldedRow row) {
        return ROW_BYTES + stringBytes(identity) + mapBytes(row.key()) + mapBytes(row.data());
    }

    /** {@link FoldedRow}, its two {@link LinkedHashMap}s and the table entry that holds the row. */
    private static final long ROW_BYTES = 160L;

    /** One {@code LinkedHashMap.Entry} (which carries before/after links) plus its table slot. */
    private static final long ENTRY_BYTES = 64L;

    /** A {@link String} header plus its (compact, one byte per Latin-1 character) array header. */
    private static final long STRING_BYTES = 48L;

    /**
     * A protobuf {@code Value}: object header, the generated message's {@code memoizedSize} and
     * {@code unknownFields} reference, the oneof case, and the oneof's own field or reference.
     */
    private static final long VALUE_BYTES = 40L;

    /** The header of the {@code byte[]} behind a bytes value. */
    private static final long ARRAY_BYTES = 16L;

    private static long mapBytes(Map<String, Value> columns) {
        long bytes = 0L;
        for (Map.Entry<String, Value> column : columns.entrySet()) {
            bytes += ENTRY_BYTES + stringBytes(column.getKey()) + valueBytes(column.getValue());
        }
        return bytes;
    }

    private static long valueBytes(Value value) {
        return VALUE_BYTES + switch (value.getVCase()) {
            case STRING_VALUE -> stringBytes(value.getStringValue());
            case DECIMAL_VALUE -> stringBytes(value.getDecimalValue());
            case BYTES_VALUE -> ARRAY_BYTES + value.getBytesValue().size();
            // int, double, bool and NULL are held inside the wrapper counted above
            case INT_VALUE, DOUBLE_VALUE, BOOL_VALUE, IS_NULL, V_NOT_SET -> 0L;
        };
    }

    private static long stringBytes(String text) {
        return STRING_BYTES + text.length();
    }

    /**
     * Build a deterministic, collision-free identity string for a row's key columns.
     *
     * <p>Columns are sorted by name and each {@code name=value} pair is length-prefixed, so distinct
     * key tuples can never concatenate to the same string. Each value carries a type tag: {@code N}
     * for SQL NULL, {@code B} for bytes (hex-encoded, so equal {@code byte[]}s match), {@code V} for
     * every other scalar (its {@code toString()}). The tag keeps a NULL key distinct from the literal
     * string {@code "null"} and a bytes key distinct from a same-looking string.</p>
     */
    private static String identity(Map<String, Value> keyMap) {
        StringBuilder sb = new StringBuilder();
        keyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getKey();
                    String value = encode(entry.getValue());
                    sb.append(name.length()).append(':').append(name)
                            .append('=').append(value.length()).append(':').append(value);
                });
        return sb.toString();
    }

    /**
     * Type-tagged encoding of a key value so distinct wire types never share an identity: int
     * {@code 1}, string {@code "1"}, and bool {@code true} must address different rows (review r4).
     * Decimals are scale-normalized ({@code 1.5} == {@code 1.50}) so trailing-zero variance across
     * client code paths still addresses one row.
     */
    private static String encode(Value value) {
        return switch (value.getVCase()) {
            case INT_VALUE -> "I" + value.getIntValue();
            case DOUBLE_VALUE -> "D" + Double.doubleToLongBits(value.getDoubleValue());
            case STRING_VALUE -> "S" + value.getStringValue();
            case BOOL_VALUE -> "L" + value.getBoolValue();
            case DECIMAL_VALUE -> "M" + normalizeDecimal(value.getDecimalValue());
            case BYTES_VALUE -> "B" + HexFormat.of().formatHex(value.getBytesValue().toByteArray());
            case IS_NULL, V_NOT_SET -> "N";
        };
    }

    /**
     * The identity form of a decimal key column, so {@code 1.0} and {@code 1.00} fold as one row.
     *
     * <p>A value {@link java.math.BigDecimal} cannot parse keeps the token itself as its identity
     * (issue #215, review round 2). PostgreSQL {@code numeric} holds {@code NaN} and
     * {@code ±Infinity} and compares {@code NaN} equal to itself, so it is a usable key — and the
     * bare parse here threw on every one of them, out of {@code apply}, aborting the <b>whole
     * site's</b> fold rather than one table. That is a larger blast radius than the per-table skip
     * this ticket set out to remove, and it is deterministic: every following nightly build ends
     * the same way, with the pointer and retention frozen.</p>
     *
     * <p>The non-finite spellings are canonicalised so a client sending {@code nan} and {@code NaN}
     * for the same source row does not fold into two identities.</p>
     */
    private static String normalizeDecimal(String token) {
        try {
            return new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            String trimmed = token.trim();
            String unsigned = trimmed.startsWith("+") || trimmed.startsWith("-")
                    ? trimmed.substring(1) : trimmed;
            if (trimmed.equalsIgnoreCase("nan")) {
                return "NaN";
            }
            if (unsigned.equalsIgnoreCase("infinity") || unsigned.equalsIgnoreCase("inf")) {
                return trimmed.startsWith("-") ? "-Infinity" : "Infinity";
            }
            return trimmed;
        }
    }

    private static Map<String, Map<String, FoldedRow>> deepCopy(Map<String, Map<String, FoldedRow>> source) {
        Map<String, Map<String, FoldedRow>> copy = new LinkedHashMap<>();
        source.forEach((table, rows) -> {
            Map<String, FoldedRow> rowsCopy = new LinkedHashMap<>();
            rows.forEach((identity, row) -> rowsCopy.put(identity,
                    new FoldedRow(new LinkedHashMap<>(row.key()), new LinkedHashMap<>(row.data()))));
            copy.put(table, rowsCopy);
        });
        return copy;
    }
}
