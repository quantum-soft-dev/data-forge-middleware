package com.bitbi.dfm.delta.application;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #292 — a bootstrap build whose whole history is a {@code FULL_SNAPSHOT} session writes the
 * reload frame straight from the record stream, because the wire contract says every record of such
 * a session is an {@code INSERT} and therefore the fold is the identity map.
 *
 * <p>Two things have to hold for that shortcut to be safe. The frame it writes must be the frame the
 * general path would have written — <b>as a set of records</b>, since the streaming form emits them
 * in arrival order rather than grouped by table, and the frame-local {@code seq} is ignored on
 * re-fold. And a violation of the contract must be <b>refused</b>, not silently collapsed: two
 * records sharing a key would be one row through the fold and two rows here, which is the one way
 * this path could produce an artifact the general path never would.</p>
 */
class BootstrapFrameWriterTest {

    @Test
    void writesTheSameRecordSetTheGeneralPathWouldFold() {
        List<ChangeRecord> input = List.of(
                insert("orders", 1, "alpha"),
                insert("items", 7, "widget"),
                insert("orders", 2, "beta"),
                insert("items", 8, "gadget"),
                insert("orders", 3, "gamma"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BootstrapFrameWriter.FrameManifest manifest;
        try (BootstrapFrameWriter writer = BootstrapFrameWriter.open(out)) {
            input.forEach(writer::accept);
            manifest = writer.manifest();
        }

        assertEquals(List.of("orders", "items"), manifest.tables(), "tables in first-seen order");
        assertEquals(3L, manifest.rowCounts().get("orders"));
        assertEquals(2L, manifest.rowCounts().get("items"));
        assertEquals(5L, manifest.records());

        assertEquals(asSet(CheckpointFrame.records(fold(input))),
                asSet(ChangelogCodec.parse(out.toByteArray())),
                "the streamed frame must carry exactly the records the fold would have re-emitted");
    }

    @Test
    void aRepeatedKeyIsRefusedRatherThanFoldedSilently() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BootstrapFrameWriter writer = BootstrapFrameWriter.open(out);
        writer.accept(insert("orders", 1, "alpha"));
        writer.accept(insert("orders", 2, "beta"));

        BootstrapFrameWriter.NotAFullSnapshotException refused = assertThrows(
                BootstrapFrameWriter.NotAFullSnapshotException.class,
                () -> writer.accept(insert("orders", 1, "alpha again")));
        assertTrue(refused.getMessage().contains("orders"), "the message names the table: " + refused.getMessage());
    }

    @Test
    void aNonInsertRecordIsRefused() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BootstrapFrameWriter writer = BootstrapFrameWriter.open(out);
        writer.accept(insert("orders", 1, "alpha"));

        ChangeRecord update = insert("orders", 2, "beta").toBuilder().setOp(Op.UPDATE).build();
        assertThrows(BootstrapFrameWriter.NotAFullSnapshotException.class, () -> writer.accept(update));
    }

    @Test
    void theSameKeyInTwoTablesIsNotARepeat() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BootstrapFrameWriter writer = BootstrapFrameWriter.open(out)) {
            writer.accept(insert("orders", 1, "alpha"));
            writer.accept(insert("items", 1, "widget"));
            assertEquals(2L, writer.manifest().records());
        }
    }

    /**
     * The guard is a set of 64-bit hashes rather than of identity strings — ~80 MB for five million
     * rows against gigabytes for the strings, which is the whole reason this path exists. A
     * collision is therefore possible and must fail <em>safe</em>: it sends the build down the
     * general fold path, which is correct whatever the data does. This pins that direction by
     * driving two rows that really do collide, which no realistic dataset would produce.
     */
    @Test
    void aHashCollisionRefusesRatherThanAccepting() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BootstrapFrameWriter writer = BootstrapFrameWriter.open(out, identity -> 42L);
        writer.accept(insert("orders", 1, "alpha"));
        assertThrows(BootstrapFrameWriter.NotAFullSnapshotException.class,
                () -> writer.accept(insert("orders", 2, "beta")));
    }

    private static Map<String, Map<String, ChangelogFold.FoldedRow>> fold(List<ChangeRecord> records) {
        Map<String, Map<String, ChangelogFold.FoldedRow>> state = new LinkedHashMap<>();
        records.forEach(record -> ChangelogFold.apply(state, record));
        return state;
    }

    /** Table, key and data of every record — the frame's meaning, minus the frame-local seq. */
    private static List<String> asSet(Iterable<ChangeRecord> records) {
        List<String> flattened = new ArrayList<>();
        for (ChangeRecord record : records) {
            flattened.add(record.getTable() + "|" + record.getOp() + "|" + record.getKeyMap()
                    + "|" + record.getDataMap());
        }
        flattened.sort(String::compareTo);
        return flattened;
    }

    private static ChangeRecord insert(String table, long id, String label) {
        Map<String, Value> key = new LinkedHashMap<>();
        key.put("id", Value.newBuilder().setIntValue(id).build());
        Map<String, Value> data = new LinkedHashMap<>(key);
        data.put("label", Value.newBuilder().setStringValue(label).build());
        return ChangeRecord.newBuilder()
                .setTable(table)
                .setOp(Op.INSERT)
                .setSeq(id)
                .putAllKey(key)
                .putAllData(data)
                .build();
    }
}
