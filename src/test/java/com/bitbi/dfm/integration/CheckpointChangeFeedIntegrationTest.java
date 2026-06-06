package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.3 — building a checkpoint materializes a Parquet change-feed partition per table under
 * {@code egress/{siteId}/{table}/_change_date=YYYY-MM-DD/} (CR §8.D / §12). The partition carries only
 * the rows that changed in that build (delta), so Power BI Incremental Refresh does not re-ingest the
 * whole table each day; the full floor lives in the {@code checkpoints/} snapshot. On the very first
 * checkpoint the delta equals the full state. Here we prove the partition is keyed by change date and
 * that a later build emits only the changed rows.
 */
class CheckpointChangeFeedIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @TempDir
    Path tempDir;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void materializesChangeFeedPartitionAsAllInsertFrame() throws Exception {
        seedCustomersSchema();

        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 1L, key("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann"), "amount", decVal("19.99"),
                                "joined_on", strVal("2024-03-10"))),
                rec("customers", Op.INSERT, 2L, key("id", intVal(2)),
                        data("id", intVal(2), "name", strVal("Bob"), "amount", decVal("5.00"),
                                "joined_on", strVal("2024-03-11"))));
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, records);

        checkpointService.buildCheckpoint(SITE);

        // Exactly one change-feed partition, keyed by change date, holding the floor frame.
        List<String> keys = checkpointStorage.listKeys("egress/" + SITE + "/customers/");
        assertEquals(1, keys.size(), "one change-feed partition for the floor: " + keys);
        String key = keys.get(0);
        assertTrue(key.matches("egress/" + SITE + "/customers/_change_date=\\d{4}-\\d{2}-\\d{2}/seq=2\\.parquet"),
                "partition keyed by change date: " + key);

        Path file = tempDir.resolve("feed.parquet");
        Files.write(file, checkpointStorage.download(key));

        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {

            GenericRecord first = reader.read();
            assertNotNull(first, "first row present");

            Schema avro = first.getSchema();
            assertEquals(Schema.Type.LONG, branch(avro, "id").getType(), "bigint → long");
            assertEquals(Schema.Type.STRING, branch(avro, "name").getType(), "varchar → string");
            assertTrue(branch(avro, "amount").getLogicalType() instanceof LogicalTypes.Decimal,
                    "numeric → decimal logical");
            assertEquals(LogicalTypes.date(), branch(avro, "joined_on").getLogicalType(), "date → date logical");

            assertNotNull(reader.read(), "second row present");
            assertNull(reader.read(), "exactly two rows (all-INSERT frame of the full state)");
        }
    }

    @Test
    void laterCheckpointChangeFeedContainsOnlyChangedRows() throws Exception {
        seedCustomersSchema();

        // First checkpoint: two rows -> floor + a change-feed partition (delta == full on first build).
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann"), "amount", decVal("19.99"),
                                "joined_on", strVal("2024-03-10"))),
                rec("customers", Op.INSERT, 2L, key("id", intVal(2)),
                        data("id", intVal(2), "name", strVal("Bob"), "amount", decVal("5.00"),
                                "joined_on", strVal("2024-03-11")))));
        checkpointService.buildCheckpoint(SITE);

        // Second checkpoint: only id=1 changes -> the new change-feed partition holds just that row.
        changelogSegmentService.persist(SITE, BATCH, "DELTA", 3L, List.of(
                rec("customers", Op.UPDATE, 3L, key("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Annie")))));
        checkpointService.buildCheckpoint(SITE);

        // The seq=3 partition is the delta: exactly one row (id=1), not the whole table.
        List<String> keys = checkpointStorage.listKeys("egress/" + SITE + "/customers/");
        String deltaKey = keys.stream().filter(k -> k.endsWith("seq=3.parquet")).findFirst().orElseThrow();

        Path file = tempDir.resolve("delta.parquet");
        Files.write(file, checkpointStorage.download(deltaKey));
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord row = reader.read();
            assertNotNull(row, "the changed row is present");
            assertEquals(1L, ((Number) row.get("id")).longValue(), "only the changed key id=1");
            assertEquals("Annie", row.get("name").toString(), "carries the updated value");
            assertNull(reader.read(), "exactly one changed row in the delta partition");
        }

        // The floor snapshot still holds the full state (both rows).
        String floorKey = checkpointStorage.listKeys("checkpoints/" + SITE + "/customers/").stream()
                .filter(k -> k.endsWith("seq=3/snapshot.parquet")).findFirst().orElseThrow();
        Path floor = tempDir.resolve("floor.parquet");
        Files.write(floor, checkpointStorage.download(floorKey));
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalInputFile(floor))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            assertNotNull(reader.read());
            assertNotNull(reader.read(), "floor keeps both rows");
            assertNull(reader.read());
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
