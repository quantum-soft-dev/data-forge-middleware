package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #293 — the semi-join that lets a checkpoint build hold the period's changes in heap and
 * stream the site past them.
 *
 * <p>Every case here is stated against the three operations the fold has always had, because the
 * merge's whole claim is that it produces what the fold produces. The claim itself is asserted
 * end-to-end by {@link ChangelogMergeEquivalenceTest}; this class pins the individual rules so a
 * failure says which one broke.</p>
 */
class ChangelogMergeTest {

    @Test
    void aRowTheDeltaDoesNotMentionPassesThroughUnchanged() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.INSERT, key("id", 2L), data("id", 2L, "city", "LA")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY"))));

        assertEquals(2, out.size());
        assertEquals("NY", string(out.get(0), "city"), "the frame's row must survive untouched");
        assertEquals("LA", string(out.get(1), "city"), "the delta's new row follows the frame");
    }

    @Test
    void anInsertReplacesTheFrameRowWholesaleRatherThanMergingIntoIt() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "Boston")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY", "zip", "10001"))));

        assertEquals(1, out.size());
        assertEquals("Boston", string(out.get(0), "city"));
        assertTrue(out.get(0).getDataMap().get("zip") == null,
                "an INSERT is the whole row: a column it does not carry must not survive from the frame");
    }

    @Test
    void anUpdateMergesOntoTheFrameRow() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY", "zip", "10001"))));

        assertEquals(1, out.size());
        assertEquals("Boston", string(out.get(0), "city"), "the patched column wins");
        assertEquals("10001", string(out.get(0), "zip"), "columns the patch does not name survive");
        assertEquals(1L, out.get(0).getKeyMap().get("id").getIntValue(), "the frame's key is kept");
    }

    @Test
    void severalUpdatesOfOneRowAccumulateBeforeTheyMeetTheFrame() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")));
        merge.apply(rec("u", Op.UPDATE, key("id", 1L), data("zip", "02101")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY", "zip", "10001", "tz", "ET"))));

        assertEquals("Boston", string(out.get(0), "city"));
        assertEquals("02101", string(out.get(0), "zip"));
        assertEquals("ET", string(out.get(0), "tz"), "an untouched column still survives");
    }

    @Test
    void aDeleteDropsTheFrameRow() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.DELETE, key("id", 1L), Map.of()));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY"))));

        assertTrue(out.isEmpty(), "a deleted row must leave the frame");
    }

    @Test
    void aDeleteThenUpdateRebuildsTheRowFromItsKeyRatherThanFromTheFrame() {
        // The fold removes the row on DELETE, so the UPDATE that follows finds nothing and seeds a
        // fresh row from its key columns. Merging onto the frame's row here would resurrect columns
        // the DELETE took.
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.DELETE, key("id", 1L), Map.of()));
        merge.apply(rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY", "zip", "10001"))));

        assertEquals(1, out.size());
        assertEquals("Boston", string(out.get(0), "city"));
        assertEquals(1L, out.get(0).getDataMap().get("id").getIntValue(), "the key column is seeded back");
        assertTrue(out.get(0).getDataMap().get("zip") == null,
                "the frame's other columns went with the DELETE");
    }

    @Test
    void aDeleteThenInsertKeepsOnlyTheInsertedRow() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.DELETE, key("id", 1L), Map.of()));
        merge.apply(rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "Boston")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY", "zip", "10001"))));

        assertEquals(1, out.size());
        assertEquals("Boston", string(out.get(0), "city"));
        assertTrue(out.get(0).getDataMap().get("zip") == null);
    }

    @Test
    void anUpdateWithNoFrameRowBehindItStillCarriesItsKeyColumns() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.UPDATE, key("id", 7L), data("city", "Berlin")));

        List<ChangeRecord> out = run(merge, List.of());

        assertEquals(1, out.size());
        assertEquals(7L, out.get(0).getDataMap().get("id").getIntValue(),
                "a row the frame never had must not lose its primary key");
        assertEquals("Berlin", string(out.get(0), "city"));
        assertEquals(7L, out.get(0).getKeyMap().get("id").getIntValue());
    }

    @Test
    void aDeleteOfARowNeitherSideHasEmitsNothing() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.DELETE, key("id", 9L), Map.of()));

        assertTrue(run(merge, List.of()).isEmpty());
    }

    @Test
    void aTableTheFrameNeverHadIsEmittedAfterIt() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("orders", Op.INSERT, key("id", 1L), data("id", 1L, "total", "10")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY"))));

        assertEquals(List.of("u", "orders"), out.stream().map(ChangeRecord::getTable).toList());
    }

    @Test
    void everyEmittedRecordIsAnInsert() {
        ChangelogMerge merge = new ChangelogMerge();
        merge.apply(rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")));
        merge.apply(rec("u", Op.INSERT, key("id", 3L), data("id", 3L, "city", "SF")));

        List<ChangeRecord> out = run(merge, List.of(
                frameRow("u", key("id", 1L), data("id", 1L, "city", "NY"))));

        assertTrue(out.stream().allMatch(record -> record.getOp() == Op.INSERT),
                "a checkpoint frame is an all-INSERT changelog");
    }

    @Test
    void thePartitionFilterKeepsTheSameShareOfBothSides() {
        // A row and its changes must land in the same pass, or a pass would emit a stale frame row
        // while another emits its update. Every row of the frame is claimed by exactly one pass.
        List<ChangeRecord> frame = new ArrayList<>();
        for (long id = 0; id < 40; id++) {
            frame.add(frameRow("u", key("id", id), data("id", id, "city", "old")));
        }
        List<ChangeRecord> delta = new ArrayList<>();
        for (long id = 0; id < 40; id += 2) {
            delta.add(rec("u", Op.UPDATE, key("id", id), data("city", "new")));
        }

        int partitions = 4;
        List<ChangeRecord> out = new ArrayList<>();
        for (int partition = 0; partition < partitions; partition++) {
            ChangelogMerge merge = new ChangelogMerge(partitions, partition);
            delta.forEach(merge::apply);
            out.addAll(run(merge, frame));
        }

        assertEquals(40, out.size(), "every row is claimed by exactly one partition");
        assertEquals(20, out.stream().filter(record -> "new".equals(string(record, "city"))).count(),
                "each updated row met its own update");
        assertEquals(40, out.stream().map(record -> record.getKeyMap().get("id").getIntValue())
                .distinct().count(), "no row is emitted twice");
    }

    @Test
    void heapIsChargedForTheDeltaAndGivenBackWhenItShrinks() {
        ChangelogMerge merge = new ChangelogMerge();
        long inserted = merge.apply(rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")));
        assertTrue(inserted > 0, "an inserted row costs heap");

        long deleted = merge.apply(rec("u", Op.DELETE, key("id", 1L), Map.of()));
        assertTrue(deleted < 0, "deleting it gives most of that heap back");
        assertTrue(inserted + deleted < inserted,
                "what a tombstone keeps must be less than the row it replaced");
    }

    @Test
    void aRecordOutsideThePartitionCostsNoHeap() {
        ChangelogMerge merge = new ChangelogMerge(2, 0);
        long total = 0;
        for (long id = 0; id < 20; id++) {
            total += merge.apply(rec("u", Op.INSERT, key("id", id), data("id", id, "city", "NY")));
        }
        long everything = 0;
        ChangelogMerge whole = new ChangelogMerge();
        for (long id = 0; id < 20; id++) {
            everything += whole.apply(rec("u", Op.INSERT, key("id", id), data("id", id, "city", "NY")));
        }
        assertTrue(total > 0 && total < everything,
                "one of two partitions must cost less heap than the whole delta");
    }

    private static List<ChangeRecord> run(ChangelogMerge merge, List<ChangeRecord> frame) {
        List<ChangeRecord> out = new ArrayList<>();
        frame.forEach(record -> merge.accept(record, out::add));
        merge.drain(out::add);
        return out;
    }

    private static String string(ChangeRecord record, String column) {
        Value value = record.getDataMap().get(column);
        return value == null ? null : value.getStringValue();
    }

    static ChangeRecord frameRow(String table, Map<String, Value> key, Map<String, Value> data) {
        return rec(table, Op.INSERT, key, data);
    }

    static ChangeRecord rec(String table, Op op, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(op).setSeq(1)
                .putAllKey(key).putAllData(data)
                .build();
    }

    static Map<String, Value> key(String column, long value) {
        return Map.of(column, Value.newBuilder().setIntValue(value).build());
    }

    static Map<String, Value> data(Object... pairs) {
        Map<String, Value> data = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            Object value = pairs[at + 1];
            data.put((String) pairs[at], value instanceof Long number
                    ? Value.newBuilder().setIntValue(number).build()
                    : Value.newBuilder().setStringValue((String) value).build());
        }
        return data;
    }
}
