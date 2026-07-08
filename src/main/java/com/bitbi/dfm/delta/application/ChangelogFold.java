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
     * Apply {@code records} on top of {@code initial} (typically a checkpoint state).
     *
     * @param initial starting state (not mutated)
     * @param records changelog records in sequence order
     * @return resulting state: table → row-identity → folded row
     */
    public static Map<String, Map<String, FoldedRow>> fold(
            Map<String, Map<String, FoldedRow>> initial, List<ChangeRecord> records) {

        Map<String, Map<String, FoldedRow>> state = deepCopy(initial);

        for (ChangeRecord record : records) {
            Map<String, FoldedRow> table = state.computeIfAbsent(record.getTable(), k -> new LinkedHashMap<>());
            String identity = identity(record.getKeyMap());
            switch (record.getOp()) {
                case INSERT -> table.put(identity, new FoldedRow(
                        new LinkedHashMap<>(record.getKeyMap()),
                        new LinkedHashMap<>(record.getDataMap())));
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
                    table.put(identity, new FoldedRow(key, mergedData));
                }
                case DELETE -> table.remove(identity);
                default -> {
                }
            }
        }
        return state;
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
            case DECIMAL_VALUE -> "M" + new java.math.BigDecimal(value.getDecimalValue()).stripTrailingZeros().toPlainString();
            case BYTES_VALUE -> "B" + HexFormat.of().formatHex(value.getBytesValue().toByteArray());
            case IS_NULL, V_NOT_SET -> "N";
        };
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
