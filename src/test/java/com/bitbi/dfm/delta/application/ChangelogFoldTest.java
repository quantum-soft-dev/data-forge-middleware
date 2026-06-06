package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T3.1 — fold engine: applying a changelog (INSERT/UPDATE/DELETE) over a starting state yields the
 * current per-key row state, honoring deletes and merging updates.
 */
class ChangelogFoldTest {

    @Test
    void appliesInsertUpdateDeleteWithinOneFold() {
        Map<String, Map<String, Map<String, Object>>> state = ChangelogFold.fold(Map.of(), java.util.List.of(
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")),
                rec("u", Op.INSERT, key("id", 2L), data("id", 2L, "city", "LA")),
                rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")),
                rec("u", Op.DELETE, key("id", 2L), Map.of())));

        Map<String, Map<String, Object>> table = state.get("u");
        assertEquals(1, table.size(), "id=2 must be deleted");
        Map<String, Object> row = table.values().iterator().next();
        assertEquals("Boston", row.get("city"), "update must merge");
        assertEquals(1L, row.get("id"), "insert column preserved through update");
    }

    @Test
    void continuesFromPriorState() {
        Map<String, Map<String, Map<String, Object>>> afterInsert = ChangelogFold.fold(Map.of(), java.util.List.of(
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "name", "Ann"))));

        Map<String, Map<String, Map<String, Object>>> afterUpdate = ChangelogFold.fold(afterInsert, java.util.List.of(
                rec("u", Op.UPDATE, key("id", 1L), data("name", "Annie"))));

        Map<String, Object> row = afterUpdate.get("u").values().iterator().next();
        assertEquals("Annie", row.get("name"));
        assertEquals(1L, row.get("id"));
    }

    private static ChangeRecord rec(String table, Op op, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(String col, long v) {
        return Map.of(col, Value.newBuilder().setIntValue(v).build());
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], toValue(kv[i + 1]));
        }
        return m;
    }

    private static Value toValue(Object o) {
        if (o instanceof Long l) {
            return Value.newBuilder().setIntValue(l).build();
        }
        return Value.newBuilder().setStringValue((String) o).build();
    }
}
