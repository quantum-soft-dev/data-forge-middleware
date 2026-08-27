package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Issue #293, the acceptance criterion in one assertion: the frame the merge writes holds the rows
 * the fold would have folded, in the same per-table order.
 *
 * <p>Stated as an equality against the code being replaced rather than as a list of expectations,
 * because that is the promise the ticket makes — the streamed build must be indistinguishable from
 * the folded one for {@code INSERT}, merging {@code UPDATE} and {@code DELETE} alike, degraded
 * values included. Both sides start from the same seed frame and see the same records; one folds
 * the site, the other streams it.</p>
 *
 * <p><b>Mutation:</b> dropping the patch marker in {@link ChangelogMerge} (so every update is
 * treated as a whole row), or emitting a re-created row where the frame had it instead of at the end
 * of its table, fails these.</p>
 */
class ChangelogMergeEquivalenceTest {

    @Test
    void aScriptedHistoryProducesTheFoldSFrameRowForRowAndInOrder() {
        List<ChangeRecord> baseline = baselineHistory();
        List<ChangeRecord> delta = deltaHistory();

        assertEquals(byTable(foldedFrame(baseline, delta)), byTable(mergedFrame(baseline, delta)));
    }

    @Test
    void aGeneratedHistoryOfAllThreeOperationsProducesTheFoldSFrame() {
        // Deterministic seed: a failure has to be reproducible, and a random one that cannot be
        // re-run is a rumour rather than a test.
        Random random = new Random(29_3L);
        List<ChangeRecord> baseline = new ArrayList<>();
        for (long id = 0; id < 300; id++) {
            baseline.add(rec(id % 3 == 0 ? "orders" : "customers", Op.INSERT, key(id),
                    data("id", id, "city", "city-" + id, "zip", "z" + id)));
        }
        List<ChangeRecord> delta = new ArrayList<>();
        for (int at = 0; at < 600; at++) {
            long id = random.nextInt(360);
            String table = id % 3 == 0 ? "orders" : "customers";
            delta.add(switch (random.nextInt(3)) {
                case 0 -> rec(table, Op.INSERT, key(id), data("id", id, "city", "new-" + at));
                case 1 -> rec(table, Op.UPDATE, key(id), data("zip", "z" + at));
                default -> rec(table, Op.DELETE, key(id), Map.of());
            });
        }

        assertEquals(byTable(foldedFrame(baseline, delta)), byTable(mergedFrame(baseline, delta)));
    }

    @Test
    void degradedAndExoticValuesAreCarriedIdentically() {
        List<ChangeRecord> baseline = List.of(
                rec("t", Op.INSERT, key(1L), data("id", 1L, "amount", decimal("1.50"), "blob", bytes("ab"))),
                rec("t", Op.INSERT, key(2L), data("id", 2L, "amount", decimal("NaN"), "nothing", nul())),
                rec("t", Op.INSERT, key(3L), data("id", 3L, "amount", decimal("-Infinity"))));
        List<ChangeRecord> delta = List.of(
                rec("t", Op.UPDATE, key(1L), data("amount", decimal("nan"))),
                rec("t", Op.UPDATE, key(2L), data("nothing", decimal("2.00"))),
                rec("t", Op.DELETE, key(3L), Map.of()),
                rec("t", Op.INSERT, key(4L), data("id", 4L, "blob", bytes("ff00"))));

        assertEquals(byTable(foldedFrame(baseline, delta)), byTable(mergedFrame(baseline, delta)));
    }

    @Test
    void aRowDeletedAndCreatedAgainMovesToTheEndOfItsTableJustAsTheFoldMovesIt() {
        List<ChangeRecord> baseline = List.of(
                rec("t", Op.INSERT, key(1L), data("id", 1L, "city", "NY")),
                rec("t", Op.INSERT, key(2L), data("id", 2L, "city", "LA")));
        List<ChangeRecord> delta = List.of(
                rec("t", Op.DELETE, key(1L), Map.of()),
                rec("t", Op.INSERT, key(3L), data("id", 3L, "city", "SF")),
                rec("t", Op.INSERT, key(1L), data("id", 1L, "city", "Boston")));

        List<Row> merged = byTable(mergedFrame(baseline, delta)).get("t");
        assertEquals(byTable(foldedFrame(baseline, delta)).get("t"), merged);
        assertEquals(List.of(2L, 3L, 1L), merged.stream()
                        .map(row -> row.data().get("id").getIntValue()).toList(),
                "the re-created row goes where the fold's remove-and-append puts it");
    }

    @Test
    void theMergedFrameIsWhatItClaimsToBeARealSeedForTheNextBuild() {
        List<ChangeRecord> baseline = baselineHistory();
        List<ChangeRecord> delta = deltaHistory();

        assertEquals(ChangelogFold.fold(Map.of(), foldedFrame(baseline, delta)),
                ChangelogFold.fold(Map.of(), mergedFrame(baseline, delta)),
                "re-folding either frame from empty must give the same state");
    }

    @Test
    void partitioningTheMergeDoesNotChangeWhichRowsSurviveOrWhatTheyHold() {
        List<ChangeRecord> baseline = baselineHistory();
        List<ChangeRecord> delta = deltaHistory();

        List<ChangeRecord> whole = mergedFrame(baseline, delta);
        List<ChangeRecord> partitioned = new ArrayList<>();
        int partitions = 5;
        for (int partition = 0; partition < partitions; partition++) {
            ChangelogMerge merge = new ChangelogMerge(partitions, partition);
            delta.forEach(merge::apply);
            List<ChangeRecord> frame = frameOf(baseline);
            frame.forEach(record -> merge.accept(record, partitioned::add));
            merge.drain(partitioned::add);
        }

        // Per table, as a bag: the fallback trades row order for heap — it emits partition by
        // partition — and that is the one property it does not keep. What it must keep is the rows.
        byTable(whole).forEach((table, rows) -> assertEquals(
                rows.stream().sorted(Row.BY_KEY).toList(),
                byTable(partitioned).get(table).stream().sorted(Row.BY_KEY).toList(),
                "partition " + partitions + " must hold the same rows for table " + table));
        assertFalse(whole.isEmpty(), "the fixture must actually produce rows");
    }

    // --- the two paths -------------------------------------------------------------------------

    /** Today's path: fold the seed frame and then the delta, and re-emit the state as a frame. */
    private static List<ChangeRecord> foldedFrame(List<ChangeRecord> baseline, List<ChangeRecord> delta) {
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        frameOf(baseline).forEach(record -> ChangelogFold.apply(state, record));
        delta.forEach(record -> ChangelogFold.apply(state, record));
        return CheckpointFrame.toRecords(state);
    }

    /** The new path: fold the delta only, and stream the seed frame past it. */
    private static List<ChangeRecord> mergedFrame(List<ChangeRecord> baseline, List<ChangeRecord> delta) {
        ChangelogMerge merge = new ChangelogMerge();
        delta.forEach(merge::apply);
        List<ChangeRecord> out = new ArrayList<>();
        frameOf(baseline).forEach(record -> merge.accept(record, out::add));
        merge.drain(out::add);
        return out;
    }

    /** The seed frame both paths start from — itself the fold of the site's earlier history. */
    private static List<ChangeRecord> frameOf(List<ChangeRecord> history) {
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        history.forEach(record -> ChangelogFold.apply(state, record));
        return CheckpointFrame.toRecords(state);
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static List<ChangeRecord> baselineHistory() {
        return List.of(
                rec("customers", Op.INSERT, key(1L), data("id", 1L, "city", "NY", "zip", "10001")),
                rec("customers", Op.INSERT, key(2L), data("id", 2L, "city", "LA", "zip", "90001")),
                rec("customers", Op.INSERT, key(3L), data("id", 3L, "city", "SF", "zip", "94101")),
                rec("orders", Op.INSERT, key(10L), data("id", 10L, "total", decimal("19.99"))),
                rec("orders", Op.INSERT, key(11L), data("id", 11L, "total", decimal("5.00"))),
                // A row the earlier history only ever updated, so the frame carries a row whose
                // columns are its key plus one — the shape a merge must not mistake for a wide row.
                rec("customers", Op.UPDATE, key(4L), data("city", "Berlin")));
    }

    private static List<ChangeRecord> deltaHistory() {
        return List.of(
                rec("customers", Op.UPDATE, key(1L), data("city", "Boston")),
                rec("customers", Op.DELETE, key(2L), Map.of()),
                rec("customers", Op.INSERT, key(3L), data("id", 3L, "city", "Austin")),
                rec("customers", Op.INSERT, key(5L), data("id", 5L, "city", "Oslo", "zip", "0150")),
                rec("customers", Op.UPDATE, key(6L), data("city", "Rome")),
                rec("customers", Op.UPDATE, key(1L), data("zip", "02101")),
                rec("orders", Op.DELETE, key(10L), Map.of()),
                rec("orders", Op.UPDATE, key(10L), data("total", decimal("0.00"))),
                rec("orders", Op.UPDATE, key(11L), data("total", decimal("6.00"))),
                rec("shipments", Op.INSERT, key(20L), data("id", 20L, "carrier", "DHL")),
                rec("customers", Op.DELETE, key(4L), Map.of()));
    }

    // --- comparison ----------------------------------------------------------------------------

    /**
     * One emitted row, compared by the two maps a frame record carries. Protobuf maps compare by
     * content, which is what matters: a record's column <em>order</em> is read by nothing —
     * a snapshot is written against the declared schema and a re-fold looks columns up by name.
     */
    private record Row(Map<String, Value> key, Map<String, Value> data) {

        static final java.util.Comparator<Row> BY_KEY =
                java.util.Comparator.comparing(row -> row.key().toString());
    }

    private static Map<String, List<Row>> byTable(List<ChangeRecord> frame) {
        Map<String, List<Row>> byTable = new LinkedHashMap<>();
        for (ChangeRecord record : frame) {
            assertEquals(Op.INSERT, record.getOp(), "a frame is an all-INSERT changelog");
            byTable.computeIfAbsent(record.getTable(), table -> new ArrayList<>())
                    .add(new Row(record.getKeyMap(), record.getDataMap()));
        }
        return byTable;
    }

    // --- record builders -----------------------------------------------------------------------

    private static ChangeRecord rec(String table, Op op, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder()
                .setTable(table).setOp(op).setSeq(1)
                .putAllKey(key).putAllData(data)
                .build();
    }

    private static Map<String, Value> key(long id) {
        return Map.of("id", Value.newBuilder().setIntValue(id).build());
    }

    private static Map<String, Value> data(Object... pairs) {
        Map<String, Value> data = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            data.put((String) pairs[at], value(pairs[at + 1]));
        }
        return data;
    }

    private static Value value(Object raw) {
        if (raw instanceof Value already) {
            return already;
        }
        if (raw instanceof Long number) {
            return Value.newBuilder().setIntValue(number).build();
        }
        return Value.newBuilder().setStringValue((String) raw).build();
    }

    private static Value decimal(String token) {
        return Value.newBuilder().setDecimalValue(token).build();
    }

    private static Value bytes(String hex) {
        return Value.newBuilder()
                .setBytesValue(ByteString.copyFrom(java.util.HexFormat.of().parseHex(hex)))
                .build();
    }

    private static Value nul() {
        return Value.newBuilder().setIsNull(true).build();
    }
}
