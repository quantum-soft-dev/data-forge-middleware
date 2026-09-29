package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogCodec;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
import com.bitbi.dfm.delta.application.ParquetSchemaMapper;
import com.bitbi.dfm.delta.application.RowHash;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import com.google.protobuf.ByteString;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Issue #369 on the wired path: the client's row hash reaches the checkpoint frame and the
 * {@code snapshot.parquet} on all three ways a frame is produced — the streamed bootstrap of a
 * {@code FULL_SNAPSHOT} (#292), the incremental merge (#293), and the fold of a first build that is
 * not one whole snapshot — through the real segment storage, the real build and LocalStack.
 *
 * <p>Declares the property set {@code CheckpointBootstrapStreamingIntegrationTest} and
 * {@code CheckpointIncrementalStreamingIntegrationTest} declare, so the three share one cached
 * Spring context rather than adding a background worker to the shared database.</p>
 */
@TestPropertySource(properties = {
        "delta.checkpoint.max-fold-bytes=65536",
        "delta.checkpoint.snapshot-writers=8",
        "delta.checkpoint.streaming-bootstrap=true"
})
class CheckpointRowHashIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BASELINE_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID DELTA_BATCH = UUID.fromString("0199bab2-ca1c-3d0e-441d-adb776a62579");

    private static final String TABLE = "row_hash_customers";

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSchemaService siteSchemaService;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theStreamedBootstrapAndTheMergeCarryEachRowsHash() {
        declareSchema();
        List<ChangeRecord> snapshot = new ArrayList<>();
        for (int id = 1; id <= 6; id++) {
            // Row 6 comes from an older client: no hash.
            snapshot.add(insert(id, id, "row-" + id, id == 6 ? null : hash(id)));
        }
        changelogSegmentService.persist(SITE, BASELINE_BATCH, "FULL_SNAPSHOT", 1L, snapshot);
        checkpointService.buildCheckpoint(SITE);
        assertEquals(6L, pointer(), "the bootstrap build must have run");

        Map<Long, String> expected = new LinkedHashMap<>();
        for (long id = 1; id <= 5; id++) {
            expected.put(id, RowHash.hex(hash((int) id)));
        }
        expected.put(6L, null);
        assertEquals(expected, frameHashes(6L), "the streamed bootstrap frame");
        assertEquals(expected, snapshotHashes(), "the snapshot written from that frame");

        changelogSegmentService.persist(SITE, DELTA_BATCH, "DELTA", 7L, List.of(
                update(7, 1, "patched"),
                delete(8, 2, hash(2)),
                delete(9, 3, hash(3)),
                insert(10, 3, "recreated", hash(33)),
                insert(11, 7, "new", hash(7)),
                insert(12, 4, "replaced-by-an-older-client", null)));
        checkpointService.buildCheckpoint(SITE);
        assertEquals(12L, pointer(), "the merge build must have run");

        expected.put(1L, RowHash.hex(hash(1)));
        expected.remove(2L);
        expected.put(3L, RowHash.hex(hash(33)));
        expected.put(4L, null);
        expected.put(7L, RowHash.hex(hash(7)));
        assertEquals(expected, frameHashes(12L), "the merged frame");
        assertEquals(expected, snapshotHashes(), "the snapshot written from the merged frame");
    }

    @Test
    void aFirstBuildThatFoldsItsHistoryCarriesEachRowsHash() {
        declareSchema();
        // A DELTA session is not one whole FULL_SNAPSHOT, so the first build folds it (#292).
        changelogSegmentService.persist(SITE, BASELINE_BATCH, "DELTA", 1L, List.of(
                insert(1, 1, "a", hash(1)),
                insert(2, 2, "b", null),
                update(3, 1, "a2")));
        checkpointService.buildCheckpoint(SITE);
        assertEquals(3L, pointer(), "the folded build must have run");

        Map<Long, String> expected = new LinkedHashMap<>();
        expected.put(1L, RowHash.hex(hash(1)));
        expected.put(2L, null);
        assertEquals(expected, frameHashes(3L), "the folded frame");
        assertEquals(expected, snapshotHashes(), "the snapshot written from the fold");
    }

    private long pointer() {
        return jdbc.queryForObject(
                "SELECT last_checkpoint_seq FROM site_sync_state WHERE site_id = ?", Long.class, SITE);
    }

    /** Row id → hex hash of every row of {@code TABLE} in the frame at {@code seq}. */
    private Map<Long, String> frameHashes(long seq) {
        Map<Long, String> hashes = new LinkedHashMap<>();
        try (InputStream frame = checkpointStorage.openFrame(SITE, seq)) {
            ChangelogCodec.forEach(frame, record -> {
                if (TABLE.equals(record.getTable())) {
                    hashes.put(record.getKeyMap().get("id").getIntValue(),
                            RowHash.hex(RowHash.of(record)));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sorted(hashes);
    }

    /** Row id → {@code _row_hash} of every row of the table's published snapshot. */
    private Map<Long, String> snapshotHashes() {
        Checkpoint row = checkpointRepository.findBySiteIdAndTableName(SITE, TABLE).orElseThrow();
        Map<Long, String> hashes = new LinkedHashMap<>();
        try {
            Path file = Files.createTempFile("row-hash-snapshot", ".parquet");
            try {
                Files.write(file, checkpointStorage.download(row.getS3KeyParquet()));
                try (ParquetReader<GenericRecord> reader = AvroParquetReader
                        .<GenericRecord>builder(new LocalInputFile(file))
                        .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                        .withConf(new PlainParquetConfiguration())
                        .build()) {
                    GenericRecord record;
                    while ((record = reader.read()) != null) {
                        Object cell = record.get(ParquetSchemaMapper.ROW_HASH_COLUMN);
                        hashes.put((Long) record.get("id"), cell == null ? null : cell.toString());
                    }
                }
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFalse(hashes.isEmpty(), "the snapshot must hold rows");
        return sorted(hashes);
    }

    private static Map<Long, String> sorted(Map<Long, String> hashes) {
        Map<Long, String> sorted = new LinkedHashMap<>();
        hashes.keySet().stream().sorted().forEach(id -> sorted.put(id, hashes.get(id)));
        return sorted;
    }

    private void declareSchema() {
        siteSchemaService.upsertSchema(SITE, new SchemaUploadRequestDto(Map.of(
                TABLE, new SchemaUploadRequestDto.TableSchemaDto(
                        List.of(new SchemaUploadRequestDto.ColumnDto("id", "bigint", false),
                                new SchemaUploadRequestDto.ColumnDto("name", "varchar(255)", true)),
                        List.of("id"), null))));
    }

    private static ByteString hash(int fill) {
        byte[] bytes = new byte[RowHash.LENGTH];
        java.util.Arrays.fill(bytes, (byte) fill);
        return ByteString.copyFrom(bytes);
    }

    private static ChangeRecord insert(long seq, int id, String name, ByteString rowHash) {
        Map<String, Value> data = new LinkedHashMap<>(key(id));
        data.put("name", Value.newBuilder().setStringValue(name).build());
        ChangeRecord.Builder record = ChangeRecord.newBuilder()
                .setTable(TABLE).setOp(Op.INSERT).setSeq(seq).putAllKey(key(id)).putAllData(data);
        if (rowHash != null) {
            record.setRowHash(rowHash);
        }
        return record.build();
    }

    private static ChangeRecord update(long seq, int id, String name) {
        return ChangeRecord.newBuilder()
                .setTable(TABLE).setOp(Op.UPDATE).setSeq(seq).putAllKey(key(id))
                .putData("name", Value.newBuilder().setStringValue(name).build())
                .build();
    }

    private static ChangeRecord delete(long seq, int id, ByteString rowHash) {
        return ChangeRecord.newBuilder()
                .setTable(TABLE).setOp(Op.DELETE).setSeq(seq).putAllKey(key(id)).setRowHash(rowHash).build();
    }

    private static Map<String, Value> key(int id) {
        return Map.of("id", Value.newBuilder().setIntValue(id).build());
    }
}
