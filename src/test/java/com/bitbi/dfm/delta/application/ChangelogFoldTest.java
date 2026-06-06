package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.1 / T3.5a — fold engine: applying a changelog (INSERT/UPDATE/DELETE) over a starting state
 * yields the current per-key row state, honoring deletes and merging updates, and <em>retains the
 * structured key</em> per row so the state can be re-emitted as an all-INSERT checkpoint frame.
 */
class ChangelogFoldTest {

    @Test
    void appliesInsertUpdateDeleteWithinOneFold() {
        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")),
                rec("u", Op.INSERT, key("id", 2L), data("id", 2L, "city", "LA")),
                rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")),
                rec("u", Op.DELETE, key("id", 2L), Map.of())));

        Map<String, FoldedRow> table = state.get("u");
        assertEquals(1, table.size(), "id=2 must be deleted");
        FoldedRow row = table.values().iterator().next();
        assertEquals("Boston", ValueMapper.toJava(row.data().get("city")), "update must merge");
        assertEquals(1L, ValueMapper.toJava(row.data().get("id")), "insert column preserved through update");
        assertEquals(1L, ValueMapper.toJava(row.key().get("id")), "structured key retained for frame emit");
    }

    @Test
    void updateOfAbsentRowStillMaterializesKeyColumns() {
        // An UPDATE whose row is not in the starting state (e.g. its INSERT was pruned below the
        // checkpoint, or it arrives first) must still carry its key columns into the row data, so the
        // reconstructed CSV/Parquet checkpoint is not missing the primary key.
        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("u", Op.UPDATE, key("id", 7L), data("city", "Berlin"))));

        FoldedRow row = state.get("u").values().iterator().next();
        assertEquals(7L, ValueMapper.toJava(row.data().get("id")), "key column must be present in data");
        assertEquals("Berlin", ValueMapper.toJava(row.data().get("city")));
        assertEquals(7L, ValueMapper.toJava(row.key().get("id")), "structured key retained");
    }

    @Test
    void continuesFromPriorState() {
        Map<String, Map<String, FoldedRow>> afterInsert = ChangelogFold.fold(Map.of(), List.of(
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "name", "Ann"))));

        Map<String, Map<String, FoldedRow>> afterUpdate = ChangelogFold.fold(afterInsert, List.of(
                rec("u", Op.UPDATE, key("id", 1L), data("name", "Annie"))));

        FoldedRow row = afterUpdate.get("u").values().iterator().next();
        assertEquals("Annie", ValueMapper.toJava(row.data().get("name")));
        assertEquals(1L, ValueMapper.toJava(row.data().get("id")));
        assertEquals(1L, ValueMapper.toJava(row.key().get("id")), "key survives a prior-state fold");
    }

    @Test
    void bytesKeyFoldsDeterministically() {
        // Equal byte[] keys arriving as separate records must fold to the same row identity. Under a
        // byte[].toString() identity they would each get a distinct [B@hash, so DELETE never matches.
        Map<String, Value> insertKey = bytesKey("id", new byte[]{1, 2, 3});
        Map<String, Value> deleteKey = bytesKey("id", new byte[]{1, 2, 3}); // equal bytes, different array

        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("b", Op.INSERT, insertKey, data("v", "x")),
                rec("b", Op.DELETE, deleteKey, Map.of())));

        assertTrue(state.getOrDefault("b", Map.of()).isEmpty(),
                "DELETE with an equal byte[] key must remove the inserted row");
    }

    @Test
    void nullKeyIsDistinctFromLiteralNullString() {
        // A SQL NULL key must not collapse onto the literal string "null".
        Map<String, Value> nullKey = Map.of("k", Value.newBuilder().setIsNull(true).build());
        Map<String, Value> strKey = Map.of("k", Value.newBuilder().setStringValue("null").build());

        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("t", Op.INSERT, nullKey, data("v", "fromNull")),
                rec("t", Op.INSERT, strKey, data("v", "fromString"))));

        assertEquals(2, state.get("t").size(), "SQL NULL key must not collide with the string \"null\"");
    }

    private static Map<String, Value> bytesKey(String col, byte[] v) {
        return Map.of(col, Value.newBuilder().setBytesValue(ByteString.copyFrom(v)).build());
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
