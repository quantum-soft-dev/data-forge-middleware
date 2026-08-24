package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.DeltaEgressService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8.3 — {@code DeltaEgressService} materializes one committed segment as per-table delta Parquet
 * files under {@code egress/{siteId}/{table}/delta/} and marks the segment egressed. Tables
 * without a declared schema are skipped (logged), but the segment is still marked — the queue
 * must drain. Re-running is idempotent (same key, overwritten).
 */
class DeltaEgressIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @TempDir
    Path tempDir;

    @Autowired
    private DeltaEgressService egressService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private S3CheckpointStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void purgeEgress() {
        // The bucket is shared across the suite; the "schema-less table skipped" assertion
        // must not depend on what other tests uploaded under this site's egress prefix.
        purgeEgressPrefix(SITE);
    }

    @BeforeEach
    void seedCustomersSchema() {
        String schemaJson = """
                {
                  "tables": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "name", "type": "varchar(255)", "nullable": true}
                      ],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE);
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')",
                UUID.randomUUID(), SITE, schemaJson);
    }

    @Test
    void writesDeltaParquetPerSchemaTableAndMarksSegmentEgressed() throws Exception {
        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 1L, Map.of("id", intVal(1)),
                        data("id", intVal(1), "name", strVal("Ann"))),
                rec("customers", Op.DELETE, 2L, Map.of("id", intVal(1)), Map.of()),
                // no schema declared for this table -> skipped, but must not block the segment
                rec("orders", Op.INSERT, 3L, Map.of("id", intVal(10)),
                        data("id", intVal(10), "name", strVal("o-1"))));
        ChangelogSegment segment = changelogSegmentService.persist(SITE, BATCH, "DELTA", 1L, records);

        egressService.egressSegment(segment);

        // Segment left the pending queue.
        ChangelogSegment reloaded = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        assertNotNull(reloaded.getEgressAt(), "segment marked egressed");

        // customers has a schema -> delta file exists, carries _op/_seq + typed columns, seq order.
        String key = "egress/" + SITE + "/customers/delta/" + seqRange(1L, 3L) + ".parquet";
        assertTrue(storage.exists(key), "delta parquet written: " + key);

        List<GenericRecord> rows = readBack(storage.download(key));
        assertEquals(2, rows.size());
        assertEquals("INSERT", rows.get(0).get("_op").toString());
        assertEquals(1L, rows.get(0).get("_seq"));
        assertEquals("Ann", rows.get(0).get("name").toString());
        assertEquals("DELETE", rows.get(1).get("_op").toString());
        assertEquals(1L, rows.get(1).get("id"), "DELETE carries key columns");

        // orders has no schema -> no delta file.
        assertTrue(storage.listKeys("egress/" + SITE + "/orders/").isEmpty(), "schema-less table skipped");
    }

    @Test
    void egressIsIdempotentOnRerun() {
        List<ChangeRecord> records = List.of(
                rec("customers", Op.INSERT, 5L, Map.of("id", intVal(2)),
                        data("id", intVal(2), "name", strVal("Bob"))));
        ChangelogSegment segment = changelogSegmentService.persist(SITE, BATCH, "DELTA", 5L, records);

        egressService.egressSegment(segment);
        egressService.egressSegment(segment); // re-run (e.g. worker crash after S3 write)

        String key = "egress/" + SITE + "/customers/delta/" + seqRange(5L, 5L) + ".parquet";
        assertTrue(storage.exists(key));
    }

    /** Zero-padded seq range so lexicographic S3 listing order equals numeric seq order. */
    private static String seqRange(long first, long last) {
        return String.format("seq=%019d-%019d", first, last);
    }

    private List<GenericRecord> readBack(byte[] parquet) throws Exception {
        Path file = tempDir.resolve(UUID.randomUUID() + ".parquet");
        Files.write(file, parquet);
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

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
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
}
