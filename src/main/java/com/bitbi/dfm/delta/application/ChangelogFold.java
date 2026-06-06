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
 * UPDATE merges the changed columns, DELETE removes the row. The result is
 * {@code table → identity → row(column → value)} — the basis for checkpoints, CSV reconstruction,
 * and re-baseline.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ChangelogFold {

    private static final String KEY_SEPARATOR = "";

    private ChangelogFold() {
    }

    /**
     * Apply {@code records} on top of {@code initial} (typically a checkpoint state).
     *
     * @param initial starting state (not mutated)
     * @param records changelog records in sequence order
     * @return resulting state: table → row-identity → row(column → typed value)
     */
    public static Map<String, Map<String, Map<String, Object>>> fold(
            Map<String, Map<String, Map<String, Object>>> initial, List<ChangeRecord> records) {

        Map<String, Map<String, Map<String, Object>>> state = deepCopy(initial);

        for (ChangeRecord record : records) {
            Map<String, Map<String, Object>> table =
                    state.computeIfAbsent(record.getTable(), k -> new LinkedHashMap<>());
            String identity = identity(record.getKeyMap());
            switch (record.getOp()) {
                case INSERT -> table.put(identity, new LinkedHashMap<>(ValueMapper.toMap(record.getDataMap())));
                case UPDATE -> table.computeIfAbsent(identity, k -> new LinkedHashMap<>())
                        .putAll(ValueMapper.toMap(record.getDataMap()));
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

    private static Map<String, Map<String, Map<String, Object>>> deepCopy(
            Map<String, Map<String, Map<String, Object>>> source) {
        Map<String, Map<String, Map<String, Object>>> copy = new LinkedHashMap<>();
        source.forEach((table, rows) -> {
            Map<String, Map<String, Object>> rowsCopy = new LinkedHashMap<>();
            rows.forEach((identity, row) -> rowsCopy.put(identity, new LinkedHashMap<>(row)));
            copy.put(table, rowsCopy);
        });
        return copy;
    }
}
