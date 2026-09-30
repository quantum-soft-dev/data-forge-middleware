package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogCodec;
import com.bitbi.dfm.delta.application.ChangelogFold;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointFrame;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #374 on the wired path: a site's first checkpoint when its history is a
 * {@code FULL_SNAPSHOT} followed by a DELTA or CONTINUOUS tail — the ordinary shape, since a client
 * sends a DELTA every day and the build runs once a night.
 *
 * <p>{@code delta.checkpoint.max-fold-bytes} is pinned far below what a fold of the snapshot costs,
 * so the general fold — which is what this history used to take — is refused with
 * {@code fold_too_large}. The owner's variant 2 answers in two ticks: the first build streams the
 * prefix alone (#292) and parks the pointer at the snapshot's end, the second merges the tail into
 * that frame (#293). After the second, the frame and every {@code snapshot.parquet} must equal
 * {@link ChangelogFold#fold} of the whole history, by the equality rule of
 * {@code ChangelogMergeEquivalenceTest}: the rows, their values and the per-table order.</p>
 *
 * <p>Declares the property set of {@code CheckpointBootstrapStreamingIntegrationTest}, so the two
 * share one cached Spring context.</p>
 */
@TestPropertySource(properties = {
        "delta.checkpoint.max-fold-bytes=65536",
        "delta.checkpoint.snapshot-writers=8",
        "delta.checkpoint.streaming-bootstrap=true"
})
class CheckpointSnapshotPrefixIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID SNAPSHOT_BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID TAIL_BATCH = UUID.fromString("0199bab2-ca1c-3d0e-441d-adb776a62579");

    private static final int TABLES = 3;
    /** 1200 rows: a fold several times the 64 KiB budget, a tail far below it. */
    private static final int ROWS_PER_TABLE = 400;
    private static final int SEAL_RECORDS = 500;

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

    @ParameterizedTest
    @ValueSource(strings = {"DELTA", "CONTINUOUS"})
    void buildsAFirstCheckpointOfASnapshotAndATailInTwoTicks(String tailMode) {
        declareSchemas();
        List<ChangeRecord> snapshot = seedSnapshot();
        List<ChangeRecord> tail = seedTail(tailMode, snapshot.size());
        List<ChangeRecord> history = new ArrayList<>(snapshot);
        history.addAll(tail);
        long snapshotEnd = snapshot.size();
        long tailEnd = history.size();

        checkpointService.buildCheckpoint(SITE);

        assertEquals(snapshotEnd, pointer(), "the first build parks the pointer at the snapshot's end");
        assertEquals(frameByTable(foldOf(snapshot)), frameByTable(frame(snapshotEnd)),
                "the first frame is the fold of the snapshot");
        for (int table = 0; table < TABLES; table++) {
            assertEquals(ROWS_PER_TABLE, checkpoint(table).getRowCount(),
                    "table " + table + " is published from the snapshot at once");
        }

        checkpointService.buildCheckpoint(SITE);

        assertEquals(tailEnd, pointer(), "the second build merges the tail");
        List<ChangeRecord> expected = foldOf(history);
        assertEquals(frameByTable(expected), frameByTable(frame(tailEnd)),
                "the merged frame is the fold of the whole history");
        Map<String, List<String>> expectedRows = rowsByTable(expected);
        for (int table = 0; table < TABLES; table++) {
            Checkpoint row = checkpoint(table);
            assertEquals(tailEnd, row.getSeq(), "table " + table + " was rewritten by the merge");
            assertEquals(expectedRows.get(tableName(table)), snapshotRows(row),
                    "the snapshot of table " + table + " is the fold of the whole history");
        }
    }

    private long pointer() {
        return jdbc.queryForObject(
                "SELECT last_checkpoint_seq FROM site_sync_state WHERE site_id = ?", Long.class, SITE);
    }

    private Checkpoint checkpoint(int table) {
        return checkpointRepository.findBySiteIdAndTableName(SITE, tableName(table)).orElseThrow();
    }

    private static String tableName(int table) {
        return "prefix_t" + table;
    }

    private static List<ChangeRecord> foldOf(List<ChangeRecord> history) {
        return CheckpointFrame.toRecords(ChangelogFold.fold(Map.of(), history));
    }

    private List<ChangeRecord> frame(long seq) {
        List<ChangeRecord> records = new ArrayList<>();
        try (InputStream frame = checkpointStorage.openFrame(SITE, seq)) {
            ChangelogCodec.forEach(frame, record -> {
                if (record.getTable().startsWith("prefix_t")) {
                    records.add(record);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }

    /** {@code ChangelogMergeEquivalenceTest}'s rule: per table, row for row and in order; no seq. */
    private static Map<String, List<String>> frameByTable(List<ChangeRecord> frame) {
        Map<String, List<String>> byTable = new TreeMap<>();
        for (ChangeRecord record : frame) {
            byTable.computeIfAbsent(record.getTable(), table -> new ArrayList<>())
                    .add(record.getOp() + "|" + record.getKeyMap() + "|" + record.getDataMap());
        }
        return byTable;
    }

    /** A frame's rows as the {@code id|name} a snapshot row carries, per table, in frame order. */
    private static Map<String, List<String>> rowsByTable(List<ChangeRecord> frame) {
        Map<String, List<String>> byTable = new TreeMap<>();
        for (ChangeRecord record : frame) {
            byTable.computeIfAbsent(record.getTable(), table -> new ArrayList<>())
                    .add(record.getDataMap().get("id").getIntValue() + "|"
                            + record.getDataMap().get("name").getStringValue());
        }
        return byTable;
    }

    private List<String> snapshotRows(Checkpoint row) {
        List<String> rows = new ArrayList<>();
        try {
            Path file = Files.createTempFile("prefix-snapshot", ".parquet");
            try {
                Files.write(file, checkpointStorage.download(row.getS3KeyParquet()));
                try (ParquetReader<GenericRecord> reader = AvroParquetReader
                        .<GenericRecord>builder(new LocalInputFile(file))
                        .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                        .withConf(new PlainParquetConfiguration())
                        .build()) {
                    GenericRecord record;
                    while ((record = reader.read()) != null) {
                        rows.add(record.get("id") + "|" + record.get("name"));
                    }
                }
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private void declareSchemas() {
        Map<String, SchemaUploadRequestDto.TableSchemaDto> tables = new LinkedHashMap<>();
        for (int table = 0; table < TABLES; table++) {
            tables.put(tableName(table), new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(new SchemaUploadRequestDto.ColumnDto("id", "bigint", false),
                            new SchemaUploadRequestDto.ColumnDto("name", "varchar(255)", true)),
                    List.of("id"), null));
        }
        siteSchemaService.upsertSchema(SITE, new SchemaUploadRequestDto(tables));
    }

    /** One FULL_SNAPSHOT session, tables interleaved, sealed every {@link #SEAL_RECORDS} records. */
    private List<ChangeRecord> seedSnapshot() {
        List<ChangeRecord> all = new ArrayList<>();
        List<ChangeRecord> pending = new ArrayList<>();
        long seq = 0;
        long firstSeq = 1;
        for (int id = 1; id <= ROWS_PER_TABLE; id++) {
            for (int table = 0; table < TABLES; table++) {
                ChangeRecord record = insert(tableName(table), ++seq, id, "row-" + id);
                pending.add(record);
                all.add(record);
            }
            if (pending.size() >= SEAL_RECORDS) {
                changelogSegmentService.persist(SITE, SNAPSHOT_BATCH, "FULL_SNAPSHOT", firstSeq, List.copyOf(pending));
                firstSeq = seq + 1;
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            changelogSegmentService.persist(SITE, SNAPSHOT_BATCH, "FULL_SNAPSHOT", firstSeq, List.copyOf(pending));
        }
        return all;
    }

    /**
     * Per table: an INSERT replacing a snapshot row, an UPDATE patching one, a DELETE removing one,
     * and a row the snapshot never had. A CONTINUOUS tail is sealed in two segments.
     */
    private List<ChangeRecord> seedTail(String mode, long afterSeq) {
        List<ChangeRecord> tail = new ArrayList<>();
        long seq = afterSeq;
        for (int table = 0; table < TABLES; table++) {
            String name = tableName(table);
            tail.add(insert(name, ++seq, 1, "replaced"));
            tail.add(update(name, ++seq, 2, "patched"));
            tail.add(delete(name, ++seq, 3));
            tail.add(insert(name, ++seq, 10_000, "brand new"));
        }
        if ("CONTINUOUS".equals(mode)) {
            int half = tail.size() / 2;
            changelogSegmentService.persist(SITE, TAIL_BATCH, mode, afterSeq + 1, List.copyOf(tail.subList(0, half)));
            changelogSegmentService.persist(SITE, TAIL_BATCH, mode, afterSeq + 1 + half,
                    List.copyOf(tail.subList(half, tail.size())));
        } else {
            changelogSegmentService.persist(SITE, TAIL_BATCH, mode, afterSeq + 1, tail);
        }
        return tail;
    }

    private static Map<String, Value> key(long id) {
        return Map.of("id", Value.newBuilder().setIntValue(id).build());
    }

    private static ChangeRecord insert(String table, long seq, long id, String name) {
        Map<String, Value> data = new LinkedHashMap<>(key(id));
        data.put("name", Value.newBuilder().setStringValue(name).build());
        return ChangeRecord.newBuilder().setTable(table).setOp(Op.INSERT).setSeq(seq)
                .putAllKey(key(id)).putAllData(data).build();
    }

    private static ChangeRecord update(String table, long seq, long id, String name) {
        return ChangeRecord.newBuilder().setTable(table).setOp(Op.UPDATE).setSeq(seq)
                .putAllKey(key(id))
                .putData("name", Value.newBuilder().setStringValue(name).build()).build();
    }

    private static ChangeRecord delete(String table, long seq, long id) {
        return ChangeRecord.newBuilder().setTable(table).setOp(Op.DELETE).setSeq(seq)
                .putAllKey(key(id)).build();
    }
}
