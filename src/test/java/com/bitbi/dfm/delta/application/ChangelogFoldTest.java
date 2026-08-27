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

    @Test
    void intKeyIsDistinctFromEqualStringKey() {
        // int 1 and string "1" are different rows; a type-blind identity ("V1" for both) would
        // fold them together and silently overwrite data (review r4).
        Map<String, Value> intKey = Map.of("k", Value.newBuilder().setIntValue(1L).build());
        Map<String, Value> strKey = Map.of("k", Value.newBuilder().setStringValue("1").build());

        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("t", Op.INSERT, intKey, data("v", "fromInt")),
                rec("t", Op.INSERT, strKey, data("v", "fromString"))));

        assertEquals(2, state.get("t").size(), "int 1 must not collide with string \"1\"");
    }

    @Test
    void decimalKeyIdentityIsScaleInsensitive() {
        // The same numeric key sent as "1.5" then "1.50" (trailing-zero variance across code paths)
        // must address ONE row, so a later DELETE/UPDATE lands (review r4).
        Map<String, Value> d15 = Map.of("k", Value.newBuilder().setDecimalValue("1.5").build());
        Map<String, Value> d150 = Map.of("k", Value.newBuilder().setDecimalValue("1.50").build());

        Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                rec("t", Op.INSERT, d15, data("v", "x")),
                rec("t", Op.DELETE, d150, Map.of())));

        assertTrue(state.get("t") == null || state.get("t").isEmpty(),
                "decimal 1.5 and 1.50 must address the same row so the DELETE removes it");
    }

    @Test
    void applyChargesAnInsertAndRefundsItsDelete() {
        // The fold is the checkpoint build's real heap bound (issue #152), so applying a record has
        // to say how much heap the state gained or lost by it — a row put in and taken out again
        // must leave the running total exactly where it started. Since #290 a table's column names
        // are shared by its rows, so they are charged once — on the record that introduces them —
        // and a delete does not give them back: a name outlives every row that used it. The table
        // is warmed first so this measures the row alone.
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        ChangelogFold.apply(state, rec("u", Op.INSERT, key("id", 0L), data("id", 0L, "city", "SF")));

        long inserted = ChangelogFold.apply(state,
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")));
        long deleted = ChangelogFold.apply(state, rec("u", Op.DELETE, key("id", 1L), Map.of()));

        assertTrue(inserted > 0, "an inserted row must weigh something: " + inserted);
        assertEquals(-inserted, deleted, "deleting the row must give back exactly what it cost");
        assertEquals(1, state.get("u").size(), "the row itself is gone; the warm-up row is not");
    }

    @Test
    void applyChargesOnlyTheDifferenceWhenARowIsReplaced() {
        // Re-inserting the same key replaces a row rather than adding one. Charging the new row's
        // whole weight would make a site that rewrites its rows every night look unboundedly large.
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        ChangelogFold.apply(state, rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")));

        long same = ChangelogFold.apply(state,
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "LA")));
        long wider = ChangelogFold.apply(state,
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "San Francisco")));

        assertEquals(0, same, "an equally wide replacement costs nothing");
        assertTrue(wider > 0 && wider < 200, "only the extra characters are charged: " + wider);
        assertEquals(1, state.get("u").size(), "still one row");
    }

    @Test
    void applyWeighsAWideRowAboveANarrowOne() {
        // The estimate has to scale with what actually fills the heap — columns and their values,
        // not the record count.
        Map<String, Map<String, FoldedRow>> narrow = new LinkedHashMap<>();
        Map<String, Map<String, FoldedRow>> wide = new LinkedHashMap<>();

        long narrowBytes = ChangelogFold.apply(narrow,
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")));
        long wideBytes = ChangelogFold.apply(wide, rec("u", Op.INSERT, key("id", 1L),
                data("id", 1L, "city", "NY", "notes", "x".repeat(4096), "tag", "y".repeat(512))));

        assertTrue(wideBytes > narrowBytes + 4096,
                "the wide row must carry at least its own characters: " + narrowBytes + " vs " + wideBytes);
    }

    @Test
    void applyNetsToZeroAcrossInsertUpdateAndDelete() {
        // The UPDATE branch charges only the difference between the two data maps, because the row
        // keeps the same key map object — a shortcut that is only safe if the total still returns to
        // where it started when the row goes.
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        // Warmed so the table's shared column names — charged once since #290, never refunded — are
        // not part of what this row's own arithmetic has to bring back to zero.
        ChangelogFold.apply(state, rec("u", Op.INSERT, key("id", 0L), data("id", 0L, "city", "SF")));

        long total = ChangelogFold.apply(state,
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")));
        total += ChangelogFold.apply(state,
                rec("u", Op.UPDATE, key("id", 1L), data("city", "San Francisco")));
        total += ChangelogFold.apply(state,
                rec("u", Op.UPDATE, key("id", 1L), data("city", "LA")));
        total += ChangelogFold.apply(state, rec("u", Op.DELETE, key("id", 1L), Map.of()));

        assertEquals(0, total, "the running total must come back to zero when the row is gone");
    }

    @Test
    void applyChargesAnUpdateOfARowItHasNotSeen() {
        // An UPDATE whose row is absent materializes a new row (key columns included), so it is an
        // arrival and must be charged as one — the difference-only shortcut applies to the other case.
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        // Warmed for the same reason as above: the shared column names are the table's, not the row's.
        ChangelogFold.apply(state, rec("u", Op.UPDATE, key("id", 6L), data("city", "Berlin")));

        long charged = ChangelogFold.apply(state,
                rec("u", Op.UPDATE, key("id", 7L), data("city", "Berlin")));

        assertTrue(charged > 0, "a row that was not there costs its whole weight: " + charged);
        assertEquals(-charged, ChangelogFold.apply(state, rec("u", Op.DELETE, key("id", 7L), Map.of())),
                "and removing it gives back exactly that");
    }

    @Test
    void applyChargesNothingForADeleteThatMatchesNoRow() {
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();

        assertEquals(0, ChangelogFold.apply(state, rec("u", Op.DELETE, key("id", 9L), Map.of())),
                "a delete that removed nothing cannot refund anything");
    }

    @Test
    void applyInPlaceMatchesFold() {
        // fold() is now a loop over apply(), and the equivalence is the whole reason the streaming
        // build may use one and the existing callers the other.
        List<ChangeRecord> records = List.of(
                rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY")),
                rec("u", Op.INSERT, key("id", 2L), data("id", 2L, "city", "LA")),
                rec("u", Op.UPDATE, key("id", 1L), data("city", "Boston")),
                rec("u", Op.DELETE, key("id", 2L), Map.of()));

        Map<String, Map<String, FoldedRow>> folded = ChangelogFold.fold(Map.of(), records);
        Map<String, Map<String, FoldedRow>> applied = new LinkedHashMap<>();
        records.forEach(record -> ChangelogFold.apply(applied, record));

        assertEquals(folded, applied, "streaming the records must fold to the same state");
    }

    @Test
    void applyDoesNotMutateTheRecordItFolded() {
        // The streaming build hands apply() a record it then drops; the state must own its own maps,
        // or the fold would retain the whole parsed record graph it was meant to release.
        Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        ChangeRecord record = rec("u", Op.INSERT, key("id", 1L), data("id", 1L, "city", "NY"));

        ChangelogFold.apply(state, record);
        FoldedRow row = state.get("u").values().iterator().next();

        assertTrue(row.data() != record.getDataMap(), "the row must copy the record's data map");
        assertTrue(row.key() != record.getKeyMap(), "the row must copy the record's key map");
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

    /**
     * The fold runs before any Parquet writing, so a bare `new BigDecimal` here threw out of
     * `apply` and aborted the **whole site's** build — a larger blast radius than the per-table skip
     * #215 set out to remove, and deterministic, so every following night ended the same way with
     * the pointer and retention frozen. PostgreSQL `numeric` compares NaN equal to itself, which
     * makes it a usable key (review round 2).
     */
    @org.junit.jupiter.api.Test
    void foldsARowWhoseDecimalKeyIsNonFinite() {
        com.bitbi.dfm.delta.grpc.v2.ChangeRecord insert = com.bitbi.dfm.delta.grpc.v2.ChangeRecord.newBuilder()
                .setTable("t").setOp(com.bitbi.dfm.delta.grpc.v2.Op.INSERT).setSeq(1)
                .putKey("id", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setDecimalValue("NaN").build())
                .putData("v", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setIntValue(7).build())
                .build();

        java.util.Map<String, java.util.Map<String, ChangelogFold.FoldedRow>> state =
                new java.util.LinkedHashMap<>();
        ChangelogFold.apply(state, insert);

        org.junit.jupiter.api.Assertions.assertEquals(1, state.get("t").size(),
                "a NaN key folds as one row instead of throwing out of the build");
    }

    /**
     * The fold and {@link ValueMapper} must recognise the *same* vocabulary. Two copies of it is
     * what issue #238 was — the identical sign-handling slip in both, with nothing asserting they
     * agree — so a token one calls non-finite must never keep its raw form as the other's fold
     * identity, which is how one source row becomes two in the checkpoint snapshot.
     */
    @org.junit.jupiter.api.Test
    void everySpellingValueMapperCallsNonFiniteFoldsUnderItsCanonicalIdentity() {
        String[][] groups = {
                {"NaN", "nan", "+NaN", "-NaN", "-nan", " NaN "},
                {"Infinity", "infinity", "+Infinity", "inf", "+INF"},
                {"-Infinity", "-infinity", "-inf", " -Inf "},
        };
        for (String[] group : groups) {
            java.util.Map<String, java.util.Map<String, ChangelogFold.FoldedRow>> state =
                    new java.util.LinkedHashMap<>();
            for (String spelling : group) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        ValueMapper.isNonFiniteDecimal(com.bitbi.dfm.delta.grpc.v2.Value.newBuilder()
                                .setDecimalValue(spelling).build()),
                        spelling + " must be recognised as non-finite by ValueMapper");
                ChangelogFold.apply(state, com.bitbi.dfm.delta.grpc.v2.ChangeRecord.newBuilder()
                        .setTable("t").setOp(com.bitbi.dfm.delta.grpc.v2.Op.INSERT).setSeq(1)
                        .putKey("id", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder()
                                .setDecimalValue(spelling).build())
                        .putData("v", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setIntValue(7).build())
                        .build());
            }
            org.junit.jupiter.api.Assertions.assertEquals(1, state.get("t").size(),
                    "spellings of " + group[0] + " must fold under one identity");
        }

        // The two infinities stay apart: a shared vocabulary must not collapse them the way it
        // deliberately collapses the NaN sign.
        java.util.Map<String, java.util.Map<String, ChangelogFold.FoldedRow>> both =
                new java.util.LinkedHashMap<>();
        for (String spelling : new String[]{"Infinity", "-Infinity"}) {
            ChangelogFold.apply(both, com.bitbi.dfm.delta.grpc.v2.ChangeRecord.newBuilder()
                    .setTable("t").setOp(com.bitbi.dfm.delta.grpc.v2.Op.INSERT).setSeq(1)
                    .putKey("id", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder()
                            .setDecimalValue(spelling).build())
                    .putData("v", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setIntValue(7).build())
                    .build());
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, both.get("t").size(),
                "+Infinity and -Infinity are different values");
    }

    /**
     * `nan`, `NaN` and the signed spellings are the same source row, so they must fold to one
     * identity. PostgreSQL has a single NaN — the sign carries no meaning — and a client is free to
     * send any of these spellings, which is why the sign is stripped for infinity too (issue #238).
     */
    @org.junit.jupiter.api.Test
    void nonFiniteKeySpellingsFoldToOneIdentity() {
        java.util.Map<String, java.util.Map<String, ChangelogFold.FoldedRow>> state =
                new java.util.LinkedHashMap<>();
        for (String spelling : new String[]{"NaN", "nan", "+NaN", "-NaN", "-nan"}) {
            ChangelogFold.apply(state, com.bitbi.dfm.delta.grpc.v2.ChangeRecord.newBuilder()
                    .setTable("t").setOp(com.bitbi.dfm.delta.grpc.v2.Op.INSERT).setSeq(1)
                    .putKey("id", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder()
                            .setDecimalValue(spelling).build())
                    .putData("v", com.bitbi.dfm.delta.grpc.v2.Value.newBuilder().setIntValue(7).build())
                    .build());
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, state.get("t").size(),
                "one source row must not fold into two identities");
    }
}
