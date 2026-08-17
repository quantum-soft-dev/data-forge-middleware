package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 (023) — building a checkpoint materializes the full typed Parquet snapshot per table,
 * typed from {@code site_schemas}, and attaches its S3 key to the checkpoint row. The round-trip read
 * proves the Parquet field types match the declared schema and the row count matches the folded state.
 *
 * <p>Issue #168 — the class writes its checkpoint scratch into a directory of its own rather than
 * into the machine-wide {@code java.io.tmpdir}, so the leak assertion cannot be decided by another
 * JVM on the same host. See {@link #CHECKPOINT_SCRATCH}.</p>
 */
class CheckpointParquetIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    /**
     * The scratch directory the checkpoint build writes into for this class alone (issue #168).
     * <p>
     * Unset, {@code delta.checkpoint.temp-dir} falls back to {@code java.io.tmpdir} — the
     * machine-wide directory every other JVM on the host shares, including a sibling worktree
     * running this same suite and its {@link com.bitbi.dfm.delta.application.ParquetScratchOrphanSweeper},
     * which deletes {@code checkpoint-*} by age. The leak assertion could therefore see a file
     * <em>disappear</em> between its two listings and blame a build that leaked nothing. A
     * directory nobody else can name makes the listing exact rather than differential: anything
     * left in it is this class's own leak.
     * </p>
     */
    private static final Path CHECKPOINT_SCRATCH = createScratchDirectory("checkpoint");

    /**
     * Scratch for the completed-batch writer, kept apart from {@link #CHECKPOINT_SCRATCH} so a
     * queue worker draining in this context cannot put a file in the directory the leak assertion
     * requires to be empty. Nothing here builds one; the override exists so this context's sweeper
     * has no reason to touch {@code java.io.tmpdir} either.
     */
    private static final Path BATCH_PARQUET_SCRATCH = createScratchDirectory("batch-parquet");

    @DynamicPropertySource
    static void scratchDirectories(DynamicPropertyRegistry registry) {
        registry.add("delta.checkpoint.temp-dir", CHECKPOINT_SCRATCH::toString);
        registry.add("delta.batch-parquet.temp-dir", BATCH_PARQUET_SCRATCH::toString);
    }

    @AfterAll
    static void removeScratchDirectories() throws IOException {
        deleteRecursively(CHECKPOINT_SCRATCH);
        deleteRecursively(BATCH_PARQUET_SCRATCH);
    }

    @TempDir
    Path tempDir;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

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
        assertEquals(List.of(), scratchFiles(), "the build must not leave scratch files behind");
    }

    @Test
    void rematerializesAMissingSnapshotOnceASchemaArrivesWithoutNewSegments() {
        // Issue #128: a first build without a schema records the table and advances the pointer
        // with no artifact. The next build used to see no new segments and return; now it
        // rematerializes from the frame, and the pointer stays put.
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

        Checkpoint detached = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertNull(detached.getS3KeyParquet(), "no schema → no artifact");
        long pointerAfterFirst =
                syncStateRepository.findBySiteId(SITE).orElseThrow().getLastCheckpointSeq();
        assertEquals(2L, pointerAfterFirst);

        seedCustomersSchema();
        checkpointService.buildCheckpoint(SITE);

        Checkpoint recovered = checkpointRepository.findBySiteIdAndTableName(SITE, "customers").orElseThrow();
        assertNotNull(recovered.getS3KeyParquet(), "the second build must attach Parquet from the frame");
        assertTrue(checkpointStorage.exists(recovered.getS3KeyParquet()), "Parquet object must exist");
        assertEquals(2L, recovered.getSeq());
        assertEquals(2L, recovered.getRowCount());
        assertEquals(pointerAfterFirst,
                syncStateRepository.findBySiteId(SITE).orElseThrow().getLastCheckpointSeq(),
                "rematerialize must not move the checkpoint pointer");
    }

    @Test
    void keepsItsCheckpointScratchOutOfTheMachineWideTempDirectory() throws Exception {
        // Issue #168: the leak assertion below used to list java.io.tmpdir, which every other JVM
        // on the host writes into — including a sibling worktree's suite and its
        // ParquetScratchOrphanSweeper, which deletes checkpoint-* by age. A file present in the
        // "before" listing could therefore be gone from the "after" one, failing a build that had
        // leaked nothing.
        Path shared = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path configured = Path.of(checkpointTempDirectory).toRealPath();
        assertNotEquals(shared, configured,
                "the checkpoint scratch directory must belong to this class alone");

        // Named exactly as the old prefix filter matched, so before the fix this file was part of
        // the listing the leak assertion compared. The assertion is about the decoy alone: making
        // it "the directory is empty" would restate the leak assertion and, worse, make this method
        // fail whenever a sibling method leaked — reporting a leak of our own as a foreign file,
        // which is the misdiagnosis #168 exists to remove.
        Path foreign = Files.createTempFile(shared, "checkpoint-" + SITE + "-", ".parquet");
        try {
            assertFalse(scratchFiles().contains(foreign),
                    "another JVM's scratch must not be visible to this class");
        } finally {
            Files.deleteIfExists(foreign);
        }
    }

    /**
     * Everything in the directory the service actually writes its checkpoint scratch to. The
     * directory belongs to this class (see {@link #CHECKPOINT_SCRATCH}), so the listing is exact:
     * no prefix filter narrows it, and any entry at all is a file this class's build left behind.
     */
    private List<Path> scratchFiles() throws Exception {
        try (Stream<Path> files = Files.list(Path.of(checkpointTempDirectory))) {
            return files.sorted().toList();
        }
    }

    private static Path createScratchDirectory(String purpose) {
        try {
            // Deliberately not a checkpoint-/batch-parquet- prefix: this directory itself lives in
            // the shared tmpdir, and those prefixes are what every JVM's orphan sweeper hunts for.
            Path directory = Files.createTempDirectory("dfm-it-scratch-" + purpose + "-");
            // @AfterAll is the normal exit. Because nothing sweeps this name, a run that never
            // reaches it — ctrl-C on the Gradle run, a killed daemon, a context that fails to
            // boot — would otherwise park the directory in the host's tmpdir forever, where the
            // scratch it replaced was aged out after four hours. The hook covers every exit the
            // JVM gets to observe; only SIGKILL escapes it.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(directory),
                    "remove-" + directory.getFileName()));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the " + purpose + " scratch directory", e);
        }
    }

    private static void deleteQuietly(Path directory) {
        try {
            deleteRecursively(directory);
        } catch (IOException e) {
            // A shutdown hook has nowhere to report to, and a failure here costs one stale
            // directory rather than a wrong test result.
            System.err.println("Could not remove the scratch directory " + directory + ": " + e);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
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
