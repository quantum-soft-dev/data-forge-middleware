package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.BatchParquetFinalizationService;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.ParquetCheckpointWriter;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/** PostgreSQL + LocalStack proof of the completed-batch/table artifact contract (036, issue #93). */
@DisplayName("Unified batch Parquet finalization")
class BatchParquetFinalizationIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @TempDir
    Path tempDir;

    @Autowired
    private BatchParquetFinalizationService finalizationService;

    @Autowired
    private BatchParquetArtifactRepository artifactRepository;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private ChangelogSegmentService segmentService;

    @Autowired
    private SiteSchemaService schemaService;

    @Autowired
    private S3CheckpointStorage storage;

    @Autowired
    private com.bitbi.dfm.batch.domain.BatchRepository batchRepository;

    @Autowired
    private com.bitbi.dfm.delta.application.DeltaMetrics metrics;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // The claim query is global, not site-scoped, and other classes complete Delta batches
        // (some for sites with no declared schema, leaving retryable rows behind). Since the queue
        // is ordered by updated_at, a foreign row would be handed out ahead of this test's own and
        // finalizeNext() would build somebody else's work. Own the whole queue for the duration.
        jdbc.update("DELETE FROM batch_parquet_artifacts");
        jdbc.update("DELETE FROM changelog_segments WHERE site_id = ?", SITE_ID);
        jdbc.update("DELETE FROM site_schemas WHERE site_id = ?", SITE_ID);
        purgeEgressPrefix(SITE_ID);
        declareSchemas();
    }

    @AfterEach
    void cleanUp() {
        // These rows are committed and BATCH_ID/SITE_ID are shared verbatim with
        // BatchParquetArtifactRepositoryIntegrationTest, whose inserts would then collide with
        // uk_batch_parquet_artifact (batch_id, table_name).
        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE site_id = ?", SITE_ID);
    }

    @Test
    @DisplayName("materializes one complete ordered object per table across mixed raw segments")
    void finalizesMultipleSegmentsIntoOneArtifactPerTable() throws Exception {
        seedMixedSegments();

        assertThat(finalizationService.enqueueBatch(BATCH_ID)).isEqualTo(2);
        assertThat(finalizationService.finalizeNext()).isTrue();
        assertThat(finalizationService.finalizeNext()).isFalse();

        List<BatchParquetArtifact> artifacts = artifactRepository.findByBatchId(BATCH_ID);
        assertThat(artifacts).hasSize(2)
                .allMatch(artifact -> artifact.getStatus() == BatchParquetArtifactStatus.READY);
        assertThat(artifacts).extracting(BatchParquetArtifact::getTableName)
                .containsExactlyInAnyOrder("customers", "orders");
        assertThat(storage.listKeys(batchPrefix())).containsExactlyInAnyOrderElementsOf(
                artifacts.stream().map(BatchParquetArtifact::getS3Key).toList());

        BatchParquetArtifact customers = artifact("customers");
        List<GenericRecord> customerRows = readBack(storage.download(customers.getS3Key()));
        assertThat(customerRows).extracting(row -> (Long) row.get("_seq"))
                .containsExactly(1L, 3L, 5L);
        assertThat(customerRows).extracting(row -> row.get("_op").toString())
                .containsExactly("INSERT", "UPDATE", "INSERT");
        assertThat(customerRows.get(1).get("_changed").toString()).isEqualTo("name");
        assertThat(customers.getRowCount()).isEqualTo(customerRows.size());

        BatchParquetArtifact orders = artifact("orders");
        List<GenericRecord> orderRows = readBack(storage.download(orders.getS3Key()));
        assertThat(orderRows).extracting(row -> (Long) row.get("_seq"))
                .containsExactly(2L, 4L);
        assertThat(orderRows).extracting(row -> row.get("_op").toString())
                .containsExactly("INSERT", "DELETE");
        assertThat(orders.getRowCount()).isEqualTo(orderRows.size());
    }

    @Test
    @DisplayName("a re-baseline's provisional segments are invisible to finalization")
    void ignoresProvisionalSegmentsOfAnUnfinishedSnapshot() throws Exception {
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 1L, List.of(
                record("customers", Op.INSERT, 1L, 1L, data("id", intValue(1L), "name", stringValue("Ann")))));
        // 033: a segmented re-baseline seals these mid-snapshot and only publishes them at
        // SessionEnd. Folded in early they would be baked into a READY artifact that the published
        // baseline then contradicts.
        segmentService.persistProvisional(SITE_ID, BATCH_ID, "FULL_SNAPSHOT", 2L, List.of(
                record("customers", Op.INSERT, 2L, 2L, data("id", intValue(2L), "name", stringValue("Bob")))));

        assertThat(segmentRepository.findByBatchIdOrderByFirstSeq(BATCH_ID)).hasSize(1);
        assertThat(finalizationService.enqueueBatch(BATCH_ID)).isEqualTo(1);
        assertThat(finalizationService.finalizeNext()).isTrue();

        BatchParquetArtifact customers = artifact("customers");
        assertThat(customers.getStatus()).isEqualTo(BatchParquetArtifactStatus.READY);
        assertThat(customers.getRowCount()).isEqualTo(1);
        assertThat(readBack(storage.download(customers.getS3Key())))
                .extracting(row -> (Long) row.get("_seq"))
                .containsExactly(1L);
    }

    @Test
    @DisplayName("an upload failure remains non-ready and retries the same logical object")
    void retriesUploadFailureWithoutDuplicateObjects() {
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 1L, List.of(
                record("customers", Op.INSERT, 1L, 1L, data("id", intValue(1L), "name", stringValue("Ann")))));
        assertThat(finalizationService.enqueueBatch(BATCH_ID)).isEqualTo(1);

        S3CheckpointStorage flakyStorage = spy(storage);
        doThrow(new S3CheckpointStorage.CheckpointStorageException("simulated upload failure", null))
                .doCallRealMethod()
                .when(flakyStorage).uploadBatchParquet(
                        eq(SITE_ID), eq(BATCH_ID), eq("customers"), any(UUID.class), any(Path.class));
        BatchParquetFinalizationService flakyService = service(flakyStorage, 5);

        assertThat(flakyService.finalizeNext()).isTrue();
        BatchParquetArtifact failed = artifact("customers");
        assertThat(failed.getStatus()).isEqualTo(BatchParquetArtifactStatus.FAILED);
        assertThat(failed.getS3Key()).isNull();
        assertThat(failed.getLastError()).contains("simulated upload failure");
        assertThat(storage.listKeys(batchPrefix())).isEmpty();

        jdbc.update("UPDATE batch_parquet_artifacts SET updated_at = TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE id = ?", failed.getId());
        assertThat(flakyService.finalizeNext()).isTrue();

        BatchParquetArtifact ready = artifact("customers");
        assertThat(ready.getStatus()).isEqualTo(BatchParquetArtifactStatus.READY);
        assertThat(ready.getAttemptCount()).isEqualTo(2);
        assertThat(storage.listKeys(batchPrefix())).containsExactly(ready.getS3Key());
    }

    @Test
    @DisplayName("a deterministic failure stops being claimed once it exhausts its attempts")
    void abandonsAnArtifactThatCanNeverSucceed() {
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 1L, List.of(
                record("ghost", Op.INSERT, 1L, 1L, data("id", intValue(1L)))));
        assertThat(finalizationService.enqueueBatch(BATCH_ID)).isEqualTo(1);
        BatchParquetFinalizationService cappedService = service(storage, 2);

        assertThat(cappedService.finalizeNext()).isTrue();
        coolDownFailures();
        assertThat(cappedService.finalizeNext()).isTrue();
        coolDownFailures();
        assertThat(cappedService.finalizeNext())
                .describedAs("the second failure used up the cap, so the row is terminal")
                .isFalse();

        BatchParquetArtifact abandoned = artifact("ghost");
        assertThat(abandoned.getStatus()).isEqualTo(BatchParquetArtifactStatus.ABANDONED);
        assertThat(abandoned.getAttemptCount()).isEqualTo(2);
        assertThat(abandoned.getLastError()).contains("No declared schema");
        assertThat(storage.listKeys(batchPrefix())).isEmpty();
    }

    @Test
    @DisplayName("a claim survives the process that made it and is only reclaimed after its lease")
    void claimIsDurableAndReclaimedOnlyWhenTheLeaseExpires() {
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 1L, List.of(
                record("customers", Op.INSERT, 1L, 1L, data("id", intValue(1L), "name", stringValue("Ann")))));
        assertThat(finalizationService.enqueueBatch(BATCH_ID)).isEqualTo(1);
        S3CheckpointStorage dyingStorage = spy(storage);
        doThrow(new OutOfMemoryError("row group buffer"))
                .when(dyingStorage).uploadBatchParquet(any(), any(), any(), any(), any(Path.class));

        // An Error is not caught by the finalizer: this is the "process died mid-build" shape.
        assertThatThrownBy(() -> service(dyingStorage, 5).finalizeNext())
                .isInstanceOf(OutOfMemoryError.class);

        BatchParquetArtifact stuck = artifact("customers");
        assertThat(stuck.getStatus()).isEqualTo(BatchParquetArtifactStatus.BUILDING);
        assertThat(stuck.getAttemptCount())
                .describedAs("the claim committed before the build, so the attempt is spent")
                .isEqualTo(1);

        // Nobody touches a live claim; once the lease lapses the row is picked up again.
        assertThat(service(storage, 5).finalizeNext()).isFalse();
        assertThat(leasedService(storage).finalizeNext()).isTrue();
        assertThat(artifact("customers").getStatus()).isEqualTo(BatchParquetArtifactStatus.READY);
        assertThat(artifact("customers").getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a superseded upload completing last cannot replace the winning claim's bytes")
    void lateSupersededUploadCannotOverwriteTheWinningClaim() throws Exception {
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 1L, List.of(
                record("customers", Op.INSERT, 1L, 1L,
                        data("id", intValue(1L), "name", stringValue("Ann")))));
        assertThat(finalizationService.enqueueBatch(BATCH_ID)).isEqualTo(1);

        CountDownLatch oldUploadStarted = new CountDownLatch(1);
        CountDownLatch releaseOldUpload = new CountDownLatch(1);
        AtomicReference<String> oldAttemptKey = new AtomicReference<>();
        S3CheckpointStorage delayedStorage = spy(storage);
        doAnswer(invocation -> {
            Path oldFile = invocation.getArgument(4);
            oldUploadStarted.countDown();
            if (!releaseOldUpload.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("successor did not publish before the old upload timeout");
            }
            // Make the old physical upload observably different after the writer computed its
            // metadata. With the previous shared key this late PutObject corrupts the READY row.
            Files.write(oldFile, new byte[]{0x55}, StandardOpenOption.APPEND);
            String uploaded = (String) invocation.callRealMethod();
            oldAttemptKey.set(uploaded);
            return uploaded;
        }).when(delayedStorage).uploadBatchParquet(
                eq(SITE_ID), eq(BATCH_ID), eq("customers"), any(UUID.class), any(Path.class));

        CompletableFuture<Boolean> oldFinalization = CompletableFuture.supplyAsync(
                () -> service(delayedStorage, 5).finalizeNext());
        assertThat(oldUploadStarted.await(10, TimeUnit.SECONDS)).isTrue();

        jdbc.update("UPDATE batch_parquet_artifacts "
                + "SET updated_at = TIMESTAMP '2000-01-01 00:00:00' WHERE batch_id = ?", BATCH_ID);
        assertThat(leasedService(storage).finalizeNext()).isTrue();
        BatchParquetArtifact winner = artifact("customers");
        assertThat(winner.getStatus()).isEqualTo(BatchParquetArtifactStatus.READY);
        assertThat(winner.getAttemptCount()).isEqualTo(2);
        byte[] winningBytes = storage.download(winner.getS3Key());

        releaseOldUpload.countDown();
        assertThat(oldFinalization.get(10, TimeUnit.SECONDS)).isTrue();

        assertThat(oldAttemptKey.get()).isNotNull().isNotEqualTo(winner.getS3Key());
        assertThat(storage.exists(oldAttemptKey.get())).isFalse();
        assertThat(storage.listKeys(batchPrefix())).containsExactly(winner.getS3Key());
        assertThat(winner.getFileSize()).isEqualTo((long) winningBytes.length);
        assertThat(winner.getChecksum()).isEqualTo(sha256(winningBytes));
    }

    private BatchParquetFinalizationService service(S3CheckpointStorage storageToUse, int maxAttempts) {
        return new BatchParquetFinalizationService(artifactRepository, segmentRepository,
                segmentService, schemaService, batchRepository, storageToUse, metrics, transactionManager,
                tempDir.toString(), 10_000_000L, 0, maxAttempts, 3600);
    }

    /** Same finalizer, but with an already-elapsed build lease so stale claims are reclaimed. */
    private BatchParquetFinalizationService leasedService(S3CheckpointStorage storageToUse) {
        return new BatchParquetFinalizationService(artifactRepository, segmentRepository,
                segmentService, schemaService, batchRepository, storageToUse, metrics, transactionManager,
                tempDir.toString(), 10_000_000L, 0, 5, 0);
    }

    /** Take the retry-delay out of play so a test can drive consecutive attempts. */
    private void coolDownFailures() {
        jdbc.update("UPDATE batch_parquet_artifacts SET updated_at = TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE batch_id = ? AND status = 'FAILED'", BATCH_ID);
    }

    private void seedMixedSegments() {
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 1L, List.of(
                record("customers", Op.INSERT, 1L, 1L,
                        data("id", intValue(1L), "name", stringValue("Ann"))),
                record("orders", Op.INSERT, 2L, 10L,
                        data("id", intValue(10L), "description", stringValue("First"))),
                record("customers", Op.UPDATE, 3L, 1L,
                        data("name", stringValue("Anne")))));
        segmentService.persist(SITE_ID, BATCH_ID, "DELTA", 4L, List.of(
                record("orders", Op.DELETE, 4L, 10L, Map.of()),
                record("customers", Op.INSERT, 5L, 2L,
                        data("id", intValue(2L), "name", stringValue("Bob")))));
    }

    private BatchParquetArtifact artifact(String tableName) {
        return artifactRepository.findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, tableName)
                .orElseThrow();
    }

    private String batchPrefix() {
        return "egress/%s/batches/%s/".formatted(SITE_ID, BATCH_ID);
    }

    private List<GenericRecord> readBack(byte[] parquet) throws Exception {
        Path file = tempDir.resolve(UUID.randomUUID() + ".parquet");
        Files.write(file, parquet);
        List<GenericRecord> rows = new ArrayList<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                        new LocalInputFile(file))
                .withDataModel(ParquetCheckpointWriter.logicalTypeModel())
                .withConf(new PlainParquetConfiguration())
                .build()) {
            GenericRecord row;
            while ((row = reader.read()) != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private void declareSchemas() {
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
                    },
                    "orders": {
                      "columns": [
                        {"name": "id", "type": "bigint", "nullable": false},
                        {"name": "description", "type": "varchar(255)", "nullable": true}
                      ],
                      "primaryKey": ["id"],
                      "uniqueKeys": []
                    }
                  }
                }
                """;
        jdbc.update("INSERT INTO site_schemas (id, site_id, schema_data, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?::jsonb, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), SITE_ID, schemaJson);
    }

    private static ChangeRecord record(
            String table, Op op, long seq, long id, Map<String, Value> data) {
        return ChangeRecord.newBuilder()
                .setTable(table)
                .setOp(op)
                .setSeq(seq)
                .putKey("id", intValue(id))
                .putAllData(data)
                .build();
    }

    private static Map<String, Value> data(Object... values) {
        Map<String, Value> data = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            data.put((String) values[index], (Value) values[index + 1]);
        }
        return data;
    }

    private static Value intValue(long value) {
        return Value.newBuilder().setIntValue(value).build();
    }

    private static Value stringValue(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }
}
