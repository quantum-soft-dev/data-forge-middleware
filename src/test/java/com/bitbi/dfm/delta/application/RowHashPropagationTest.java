package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #369 — {@code ChangeRecord.row_hash} is carried from the wire to every artifact a row is
 * written into, and lost nowhere on the way: the segment (codec), the fold, the three frame
 * producers (fold, streamed bootstrap, merge), and the three Parquet artifacts (delta file, batch
 * file, checkpoint snapshot). It stays out of the session's {@code content_hash}.
 *
 * <p>The rule every assertion follows: a row's hash is the one its creating record carried; an
 * {@code UPDATE} keeps the hash of the row it lands on; a {@code DELETE} record carries its INSERT's
 * hash in a delta artifact and removes the row from a checkpoint; a record without one is a null
 * cell — every row an older client wrote.</p>
 */
class RowHashPropagationTest {

    private static final ByteString H1 = RowHashTest.hashOf(0x10);
    private static final ByteString H2 = RowHashTest.hashOf(0x20);
    private static final ByteString H3 = RowHashTest.hashOf(0x30);
    private static final ByteString H4 = RowHashTest.hashOf(0x40);

    private static final TableSchema SCHEMA = new TableSchema(List.of(
            new ColumnDefinition("id", "bigint", false),
            new ColumnDefinition("name", "varchar(255)", true)),
            List.of("id"), List.of());

    @TempDir
    Path tempDir;

    @Nested
    class Segments {

        @Test
        void theCodecCarriesTheHashThroughSerializeAndParse() {
            List<ChangeRecord> records = List.of(insert(1, "a", H1), insert(2, "b", null), delete(1, H1));

            assertEquals(records, ChangelogCodec.parse(ChangelogCodec.serialize(records)));
        }

        @Test
        void theStreamingCodecCarriesItToo() {
            List<ChangeRecord> records = List.of(insert(1, "a", H1), delete(1, H1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ChangelogCodec.write(records, out);

            List<ChangeRecord> read = new ArrayList<>();
            ChangelogCodec.forEach(new ByteArrayInputStream(out.toByteArray()), read::add);

            assertEquals(records, read);
        }

        @Test
        void theContentHashLeavesTheRowHashOut() {
            List<ChangeRecord> hashed = List.of(insert(1, "a", H1), delete(1, H1));
            List<ChangeRecord> bare = List.of(insert(1, "a", null), delete(1, null));

            assertEquals(ChangelogContentHash.compute(bare), ChangelogContentHash.compute(hashed),
                    "a client that already computes content_hash must keep getting the same one");
        }
    }

    @Nested
    class Fold {

        @Test
        void anInsertSetsTheHashAndAnUpdateKeepsIt() {
            Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
            ChangelogFold.apply(state, insert(1, "a", H1));
            ChangelogFold.apply(state, update(1, "b", null));
            ChangelogFold.apply(state, update(1, "c", H2));

            FoldedRow row = only(state);
            assertEquals(H1, row.rowHash(), "an UPDATE does not re-identify the row it lands on");
            assertEquals("c", row.data().get("name").getStringValue());
        }

        @Test
        void anInsertReplacesTheHashWithItsOwnOrWithNone() {
            Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
            ChangelogFold.apply(state, insert(1, "a", H1));
            ChangelogFold.apply(state, insert(1, "b", H2));
            assertEquals(H2, only(state).rowHash());

            ChangelogFold.apply(state, insert(1, "c", null));
            assertNull(only(state).rowHash(), "an INSERT is the whole row, hash included");
        }

        @Test
        void aDeleteRemovesTheRowAndARecreatedRowTakesItsNewHash() {
            Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
            ChangelogFold.apply(state, insert(1, "a", H1));
            ChangelogFold.apply(state, delete(1, H1));
            assertTrue(state.get("t").isEmpty());

            ChangelogFold.apply(state, insert(1, "a", H3));
            assertEquals(H3, only(state).rowHash());
        }

        @Test
        void anUpdateWithNoRowSeedsOneWithTheRecordsOwnHash() {
            Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
            ChangelogFold.apply(state, update(1, "a", null));
            assertNull(only(state).rowHash(), "the shipped client sends no hash on an UPDATE");

            Map<String, Map<String, FoldedRow>> hashed = new LinkedHashMap<>();
            ChangelogFold.apply(hashed, update(1, "a", H4));
            assertEquals(H4, only(hashed).rowHash());
        }

        @Test
        void aMalformedHashReadsAsNone() {
            Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
            ChangelogFold.apply(state, insert(1, "a", ByteString.copyFrom(new byte[31])));

            assertNull(only(state).rowHash(), "only RowHash.of decides what a hash is");
        }

        @Test
        void foldingFromAStartingStateKeepsItsHashes() {
            Map<String, Map<String, FoldedRow>> seed = ChangelogFold.fold(Map.of(), List.of(insert(1, "a", H1)));

            Map<String, Map<String, FoldedRow>> folded =
                    ChangelogFold.fold(seed, List.of(insert(2, "b", H2)));

            assertEquals(H1, folded.get("t").get(ChangelogFold.identityOf(key(1))).rowHash(),
                    "the copy of the starting state must not drop its rows' hashes");
            assertEquals(H2, folded.get("t").get(ChangelogFold.identityOf(key(2))).rowHash());
        }

        @Test
        void twoRowsThatDifferOnlyByTheirHashAreNotEqual() {
            Map<String, Map<String, FoldedRow>> a = ChangelogFold.fold(Map.of(), List.of(insert(1, "a", H1)));
            Map<String, Map<String, FoldedRow>> b = ChangelogFold.fold(Map.of(), List.of(insert(1, "a", H2)));

            assertTrue(!a.equals(b), "a state comparison must see the hash, or a lost one reads as equal");
        }
    }

    @Nested
    class Frames {

        @Test
        void theFoldedFrameCarriesEachRowsHashAndReFoldsToTheSameState() {
            Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                    insert(1, "a", H1), insert(2, "b", null), insert(3, "c", H3)));

            List<ChangeRecord> frame = CheckpointFrame.toRecords(state);

            assertEquals(List.of(H1, ByteString.EMPTY, H3), frame.stream().map(ChangeRecord::getRowHash).toList());
            assertEquals(state, ChangelogFold.fold(Map.of(), frame));
        }

        @Test
        void aFrameWrittenBeforeTheFieldExistedReFoldsToRowsWithoutAHash() {
            List<ChangeRecord> oldFrame = List.of(insert(1, "a", null), insert(2, "b", null));

            Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), oldFrame);

            assertEquals(2, state.get("t").size());
            state.get("t").values().forEach(row -> assertNull(row.rowHash()));
        }

        @Test
        void theStreamedBootstrapFrameCarriesTheSnapshotsHashes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (BootstrapFrameWriter writer = BootstrapFrameWriter.open(out)) {
                writer.accept(insert(1, "a", H1));
                writer.accept(insert(2, "b", null));
                writer.accept(insert(3, "c", ByteString.copyFrom(new byte[5])));
            }

            List<ChangeRecord> frame = ChangelogCodec.parse(out.toByteArray());

            assertEquals(List.of(H1, ByteString.EMPTY, ByteString.EMPTY),
                    frame.stream().map(ChangeRecord::getRowHash).toList(),
                    "a frame only ever holds a real hash or none");
        }

        @Test
        void theMergeCarriesTheHashOnEveryPathARowCanTake() {
            List<ChangeRecord> base = CheckpointFrame.toRecords(ChangelogFold.fold(Map.of(), List.of(
                    insert(1, "untouched", H1),
                    insert(2, "patched", H2),
                    insert(3, "replaced", H3),
                    insert(4, "recreated", H4),
                    insert(5, "deleted", H1))));
            ChangelogMerge merge = new ChangelogMerge();
            merge.apply(update(2, "patched-again", null));
            merge.apply(insert(3, "replaced-again", null));
            merge.apply(delete(4, H4));
            merge.apply(insert(4, "recreated-again", H3));
            merge.apply(delete(5, H1));
            merge.apply(insert(6, "new", H2));
            merge.apply(update(7, "seeded", null));

            Map<Long, ByteString> hashes = new LinkedHashMap<>();
            List<ChangeRecord> out = new ArrayList<>();
            base.forEach(record -> merge.accept(record, out::add));
            merge.drain(out::add);
            out.forEach(record -> hashes.put(record.getKeyMap().get("id").getIntValue(), record.getRowHash()));

            assertEquals(H1, hashes.get(1L), "a frame row the delta never touched streams through");
            assertEquals(H2, hashes.get(2L), "a patched row keeps the frame row's hash");
            assertEquals(ByteString.EMPTY, hashes.get(3L), "an INSERT replaces the row, hash and all");
            assertEquals(H3, hashes.get(4L), "a deleted and re-created row has its new hash");
            assertTrue(!hashes.containsKey(5L));
            assertEquals(H2, hashes.get(6L), "a row the frame never had brings its own");
            assertEquals(ByteString.EMPTY, hashes.get(7L), "a seeded UPDATE has none from the shipped client");
            assertEquals(ChangelogFold.fold(Map.of(), foldedFrame(base, List.of(
                            update(2, "patched-again", null), insert(3, "replaced-again", null), delete(4, H4),
                            insert(4, "recreated-again", H3), delete(5, H1), insert(6, "new", H2),
                            update(7, "seeded", null)))),
                    ChangelogFold.fold(Map.of(), out), "and it is the fold's answer");
        }

        @Test
        void aPartitionedMergeCarriesTheHashesToo() {
            List<ChangeRecord> base = CheckpointFrame.toRecords(ChangelogFold.fold(Map.of(), List.of(
                    insert(1, "a", H1), insert(2, "b", H2), insert(3, "c", H3), insert(4, "d", null))));
            List<ChangeRecord> delta = List.of(update(1, "a2", null), delete(2, H2), insert(2, "b2", H4),
                    insert(9, "z", H3));

            List<ChangeRecord> out = new ArrayList<>();
            for (int partition = 0; partition < 3; partition++) {
                ChangelogMerge merge = new ChangelogMerge(3, partition);
                delta.forEach(merge::apply);
                base.forEach(record -> merge.accept(record, out::add));
                merge.drain(out::add);
            }

            assertEquals(ChangelogFold.fold(Map.of(), foldedFrame(base, delta)), ChangelogFold.fold(Map.of(), out));
        }

        private static List<ChangeRecord> foldedFrame(List<ChangeRecord> base, List<ChangeRecord> delta) {
            Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
            base.forEach(record -> ChangelogFold.apply(state, record));
            delta.forEach(record -> ChangelogFold.apply(state, record));
            return CheckpointFrame.toRecords(state);
        }
    }

    @Nested
    class Parquet {

        @Test
        void theDeltaFileCarriesTheHashOnInsertAndDeleteAndNullElsewhere() throws Exception {
            List<ChangeRecord> records = sequenced(
                    insert(1, "a", H1), update(1, "b", null), delete(1, H1), insert(2, "c", null));

            List<GenericRecord> rows = read(DeltaParquetWriter.toDeltaParquet(
                    "t", SCHEMA, records, 8L * 1024 * 1024));

            assertEquals(hexes(RowHash.hex(H1), null, RowHash.hex(H1), null), rowHashes(rows));
        }

        @Test
        void theStreamedDeltaFileCarriesItToo() throws Exception {
            Path output = tempDir.resolve("streamed.parquet");
            DeltaParquetWriter.writeDeltaParquet(output, "t", SCHEMA,
                    consumer -> sequenced(insert(1, "a", H1), delete(1, H1)).forEach(consumer),
                    Long.MAX_VALUE, 8L * 1024 * 1024, TestScratchLeases.unbounded());

            assertEquals(hexes(RowHash.hex(H1), RowHash.hex(H1)), rowHashes(read(output)));
        }

        @Test
        void theBatchFileCarriesItToo() throws Exception {
            Map<String, DeltaParquetWriter.TableWriteRequest> requests = new LinkedHashMap<>();
            requests.put("t", new DeltaParquetWriter.TableWriteRequest(
                    tempDir.resolve("batch.parquet"), SCHEMA, TestScratchLeases.unbounded()));

            DeltaParquetWriter.writeBatchDeltaParquet(requests,
                    consumer -> sequenced(insert(1, "a", H1), insert(2, "b", null), delete(1, H1)).forEach(consumer),
                    Long.MAX_VALUE, 8L * 1024 * 1024);

            assertEquals(hexes(RowHash.hex(H1), null, RowHash.hex(H1)),
                    rowHashes(read(requests.get("t").output())));
        }

        @Test
        void theFoldedSnapshotCarriesEachRowsHash() throws Exception {
            Map<String, Map<String, FoldedRow>> state = ChangelogFold.fold(Map.of(), List.of(
                    insert(1, "a", H1), insert(2, "b", null)));
            Path output = tempDir.resolve("snapshot.parquet");

            ParquetCheckpointWriter.writeParquet(output, "t", SCHEMA, state.get("t").values(),
                    FoldedRow::data, FoldedRow::rowHash, Long.MAX_VALUE, 8L * 1024 * 1024,
                    TestScratchLeases.unbounded());

            List<GenericRecord> rows = read(output);
            assertEquals(hexes(RowHash.hex(H1), null), rowHashes(rows));
            assertEquals(List.of(1L, 2L), rows.stream().map(row -> (Long) row.get("id")).toList());
        }

        @Test
        void theSnapshotWrittenFromAFrameCarriesItToo() throws Exception {
            Path output = tempDir.resolve("from-frame.parquet");
            Schema avro = ParquetSchemaMapper.toAvroSchema("t", SCHEMA);
            try (ParquetCheckpointWriter.OpenTable table = ParquetCheckpointWriter.openTable(
                    output, "t", SCHEMA, avro, Long.MAX_VALUE, 8L * 1024 * 1024, TestScratchLeases.unbounded())) {
                for (ChangeRecord record : List.of(insert(1, "a", null), insert(2, "b", H2))) {
                    table.write(record.getDataMap(), RowHash.of(record));
                }
            }

            assertEquals(hexes(null, RowHash.hex(H2)), rowHashes(read(output)));
        }

        @Test
        void aSnapshotWithoutHashesReadsAsBeforeWithANullColumn() throws Exception {
            Path output = tempDir.resolve("no-hashes.parquet");

            ParquetCheckpointWriter.writeParquet(output, "t", SCHEMA,
                    List.of(insert(1, "a", null).getDataMap()), Long.MAX_VALUE, 8L * 1024 * 1024,
                    TestScratchLeases.unbounded());

            List<GenericRecord> rows = read(output);
            assertEquals(hexes((String) null), rowHashes(rows));
            assertEquals("a", rows.getFirst().get("name").toString());
        }

        @Test
        void egressRendersTheHashOfARecordThatWentThroughASegment() throws Exception {
            UUID site = UUID.randomUUID();
            ChangelogSegmentService segments = mock(ChangelogSegmentService.class);
            SiteSchemaService schemas = mock(SiteSchemaService.class);
            S3CheckpointStorage storage = mock(S3CheckpointStorage.class);
            byte[] segmentBytes = ChangelogCodec.serialize(sequenced(insert(1, "a", H1), delete(1, H1)));
            when(segments.readRecords("changelog/key")).thenReturn(ChangelogCodec.parse(segmentBytes));
            when(schemas.getTableSchemas(site)).thenReturn(Map.of("t", SCHEMA));
            DeltaEgressService egress = new DeltaEgressService(mock(ChangelogSegmentRepository.class), segments,
                    schemas, storage, new DeltaMetrics(new SimpleMeterRegistry()),
                    new DeltaParquetProperties(8L * 1024 * 1024), 60, 7, new ApplicationShutdownSignal());

            egress.egressSegment(ChangelogSegment.create(site, UUID.randomUUID(), 1L, 2L, 2L,
                    "hash", "changelog/key", "DELTA", null));

            ArgumentCaptor<byte[]> parquet = ArgumentCaptor.forClass(byte[].class);
            verify(storage).uploadDelta(eq(site), eq("t"), anyLong(), anyLong(), parquet.capture());
            assertEquals(hexes(RowHash.hex(H1), RowHash.hex(H1)), rowHashes(read(parquet.getValue())));
        }

        private List<GenericRecord> read(byte[] parquet) throws Exception {
            Path file = Files.createTempFile(tempDir, "read", ".parquet");
            Files.write(file, parquet);
            return read(file);
        }

        private List<GenericRecord> read(Path file) throws Exception {
            List<GenericRecord> rows = new ArrayList<>();
            try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                    .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                    .withConf(new PlainParquetConfiguration())
                    .build()) {
                GenericRecord record;
                while ((record = reader.read()) != null) {
                    rows.add(record);
                }
            }
            return rows;
        }

        private static List<String> rowHashes(List<GenericRecord> rows) {
            List<String> hashes = new ArrayList<>();
            for (GenericRecord row : rows) {
                Object cell = row.get(ParquetSchemaMapper.ROW_HASH_COLUMN);
                hashes.add(cell == null ? null : cell.toString());
            }
            return hashes;
        }

        private static List<String> hexes(String... values) {
            return java.util.Arrays.asList(values);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    /** The records with seq 1..N in the order given — the writers refuse an out-of-order seq. */
    private static List<ChangeRecord> sequenced(ChangeRecord... records) {
        List<ChangeRecord> out = new ArrayList<>();
        for (ChangeRecord record : records) {
            out.add(record.toBuilder().setSeq(out.size() + 1).build());
        }
        return out;
    }

    private static FoldedRow only(Map<String, Map<String, FoldedRow>> state) {
        assertEquals(1, state.get("t").size());
        return state.get("t").values().iterator().next();
    }

    private static ChangeRecord insert(long id, String name, ByteString rowHash) {
        return build(Op.INSERT, id, Map.of("id", intVal(id), "name", strVal(name)), rowHash);
    }

    private static ChangeRecord update(long id, String name, ByteString rowHash) {
        return build(Op.UPDATE, id, Map.of("name", strVal(name)), rowHash);
    }

    private static ChangeRecord delete(long id, ByteString rowHash) {
        return build(Op.DELETE, id, Map.of(), rowHash);
    }

    private static ChangeRecord build(Op op, long id, Map<String, Value> data, ByteString rowHash) {
        ChangeRecord.Builder record = ChangeRecord.newBuilder()
                .setTable("t").setOp(op).setSeq(id)
                .putAllKey(key(id)).putAllData(data);
        if (rowHash != null) {
            record.setRowHash(rowHash);
        }
        return record.build();
    }

    private static Map<String, Value> key(long id) {
        return Map.of("id", intVal(id));
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }
}
