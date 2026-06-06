package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final String KEY_SEPARATOR = "";

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

    private static String identity(Map<String, Value> keyMap) {
        return keyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + ValueMapper.toJava(e.getValue()))
                .collect(Collectors.joining(KEY_SEPARATOR));
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
