package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 (023) — building a checkpoint materializes the full typed Parquet snapshot per table,
 * typed from {@code site_schemas}, and attaches its S3 key to the checkpoint row. The round-trip read
 * proves the Parquet field types match the declared schema and the row count matches the folded state.
 */
class CheckpointParquetIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @TempDir
    Path tempDir;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private JdbcTemplate jdbc;

    /** Resolved exactly as CheckpointService resolves it, so the leak assertion cannot go vacuous. */
    @org.springframework.beans.factory.annotation.Value("${delta.checkpoint.temp-dir:${java.io.tmpdir}}")
    private String checkpointTempDirectory;

    @Test
    void materializesTypedParquetSnapshotMatchingSiteSchema() throws Exception {
        seedCustomersSchema();

        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 1L,
                        key("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann"), "amount", decVal("19.99"),
                                "joined_on", strVal("2024-03-10"))),
                rec("customers", Op.INSERT, 2L,
                        key("id", intVal(2)),
                        data("id", intVal(2), "name", strVal("Bob"), "amount", decVal("5.00"),
                                "joined_on", strVal("2024-03-11"))));
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        checkpointService.buildCheckpoint(SITE);

        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertNotNull(checkpoint.getS3KeyParquet(), "Parquet key must be attached");
        assertTrue(checkpointStorage.exists(checkpoint.getS3KeyParquet()), "Parquet object must exist");
        assertEquals(2L, checkpoint.getRowCount());

        Path file = tempDir.resolve("snapshot.parquet");
        Files.write(file, checkpointStorage.download(checkpoint.getS3KeyParquet()));

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {

            GenericRecord first = reader.read();
            assertNotNull(first, "first row present");

            // Parquet schema types match site_schemas.
            Schema avro = first.getSchema();
            assertEquals(Schema.Type.LONG, branch(avro, "id").getType(), "bigint → long");
            assertEquals(Schema.Type.STRING, branch(avro, "name").getType(), "varchar → string");
            assertTrue(branch(avro, "amount").getLogicalType() instanceof LogicalTypes.Decimal,
                    "numeric → decimal logical");
            assertEquals(LogicalTypes.date(), branch(avro, "joined_on").getLogicalType(), "date → date logical");

            // Typed values round-trip.
            assertEquals(1L, first.get("id"));
            assertEquals("Ann", first.get("name").toString());
            assertEquals(new BigDecimal("19.99"), first.get("amount"));
            assertEquals(LocalDate.parse("2024-03-10"), first.get("joined_on"));

            assertNotNull(reader.read(), "second row present");
            // Row count matches the folded state (2 inserts).
            org.junit.jupiter.api.Assertions.assertNull(reader.read(), "exactly two rows");
        }
    }

    @Test
    void streamsALargeTableThroughDiskWithoutMaterializingItInHeap() throws Exception {
        // Issue #112: the snapshot is written to a scratch file and streamed to S3 from there, so a
        // table far larger than any single buffer completes — and leaves no file behind afterwards.
        seedCustomersSchema();
        int rowCount = 20_000;
        List<ChangeRecord> records = new java.util.ArrayList<>(rowCount);
        for (int i = 1; i <= rowCount; i++) {
            records.add(rec("customers", Op.INSERT, i,
                    key("id", intVal(i)),
                    data("id", intVal(i), "name", strVal("customer-" + i), "amount", decVal("1.25"),
                            "joined_on", strVal("2024-03-10"))));
        }
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);
        List<Path> scratchBefore = scratchSnapshots();

        checkpointService.buildCheckpoint(SITE);

        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertEquals(rowCount, checkpoint.getRowCount());
        assertNotNull(checkpoint.getS3KeyParquet(), "Parquet key must be attached");

        Path file = tempDir.resolve("large-snapshot.parquet");
        Files.write(file, checkpointStorage.download(checkpoint.getS3KeyParquet()));
        int read = 0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            while (reader.read() != null) {
                read++;
            }
        }
        assertEquals(rowCount, read, "every folded row reached the uploaded snapshot");
        assertEquals(scratchBefore, scratchSnapshots(), "the build must not leave scratch files behind");
    }

    /** The checkpoint scratch files of this site in the directory the service actually writes to. */
    private List<Path> scratchSnapshots() throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(Path.of(checkpointTempDirectory))) {
            return files.filter(path -> path.getFileName().toString().startsWith("checkpoint-" + SITE))
                    .sorted().toList();
        }
    }

    private void seedCustomersSchema() {
        String schemaJson = """
                {
                  "tables": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "name", "type": "varchar(255)", "nullable": true},
                        {"name": "amount", "type": "numeric(10,2)", "nullable": false},
                        {"name": "joined_on", "type": "date", "nullable": false}
                      ],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE);
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now(), now())",
                UUID.randomUUID(), SITE, schemaJson);
    }

    // --- helpers ---

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(String col, Value v) {
        return Map.of(col, v);
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Value) kv[i + 1]);
        }
        return m;
    }

    private static Value intVal(long v) {
        return Value.newBuilder().setIntValue(v).build();
    }

    private static Value strVal(String v) {
        return Value.newBuilder().setStringValue(v).build();
    }

    private static Value decVal(String v) {
        return Value.newBuilder().setDecimalValue(v).build();
    }

    private static Schema branch(Schema record, String field) {
        Schema s = record.getField(field).schema();
        if (s.getType() == Schema.Type.UNION) {
            return s.getTypes().stream().filter(t -> t.getType() != Schema.Type.NULL).findFirst().orElseThrow();
        }
        return s;
    }
}
