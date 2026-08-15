package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3.5a — a checkpoint frame is the folded state re-expressed as an all-INSERT changelog frame.
 * Re-folding that frame from empty MUST reproduce the exact state (key + data), which is what makes
 * the checkpoint a self-contained seed and lets pre-checkpoint segments be pruned.
 */
class CheckpointFrameTest {

    @Test
    void allInsertFrameRoundTripsTheFoldedState() {
        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("customers", Op.INSERT, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, key("id", 2L), data("id", 2L, "name", "Bob")),
                rec("orders", Op.INSERT, key("oid", 9L), data("oid", 9L, "total", "12.50")),
                rec("customers", Op.UPDATE, key("id", 1L), data("name", "Annie"))));

        List<ChangeRecord> frame = CheckpointFrame.toRecords(state);

        assertTrue(frame.stream().allMatch(r -> r.getOp() == Op.INSERT), "frame is all-INSERT");
        assertEquals(3, frame.size(), "one INSERT per surviving row");

        Map<String, Map<String, FoldedRow>> reseeded = ChangelogFold.fold(Map.of(), frame);
        assertEquals(state, reseeded, "re-folding the frame reproduces the exact state");
    }

    @Test
    void recordsStreamsTheSameAllInsertFrameWithoutCollectingFirst() {
        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("customers", Op.INSERT, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("orders", Op.INSERT, key("oid", 9L), data("oid", 9L, "total", "12.50")),
                rec("customers", Op.UPDATE, key("id", 1L), data("name", "Annie"))));

        assertEquals(CheckpointFrame.toRecords(state), copy(CheckpointFrame.records(state)),
                "the lazy view must emit the same records, in the same order, as toRecords");

        Map<String, Map<String, FoldedRow>> reseeded = ChangelogFold.fold(
                Map.of(), ChangelogCodec.parse(write(CheckpointFrame.records(state))));
        assertEquals(state, reseeded, "a streamed frame must re-fold to the exact state");
    }

    private static List<ChangeRecord> copy(Iterable<ChangeRecord> records) {
        List<ChangeRecord> copied = new ArrayList<>();
        records.forEach(copied::add);
        return copied;
    }

    private static byte[] write(Iterable<ChangeRecord> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChangelogCodec.write(records, out);
        return out.toByteArray();
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
            Object value = kv[i + 1];
            Value v = value instanceof Long l
                    ? Value.newBuilder().setIntValue(l).build()
                    : Value.newBuilder().setStringValue((String) value).build();
            m.put((String) kv[i], v);
        }
        return m;
    }
}
