package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.site.domain.TableSchema.ColumnDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchParquetFinalizationServiceTest {

    @TempDir
    Path tempDir;

    private final BatchParquetArtifactRepository artifactRepository =
            mock(BatchParquetArtifactRepository.class);
    private final ChangelogSegmentRepository segmentRepository = mock(ChangelogSegmentRepository.class);
    private final ChangelogSegmentService segmentService = mock(ChangelogSegmentService.class);
    private final SiteSchemaService schemaService = mock(SiteSchemaService.class);
    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final S3CheckpointStorage storage = mock(S3CheckpointStorage.class);
    private BatchParquetFinalizationService service;

    @BeforeEach
    void setUp() {
        service = newService(5);
    }

    private BatchParquetFinalizationService newService(int maxAttempts) {
        return new BatchParquetFinalizationService(artifactRepository, segmentRepository,
                segmentService, schemaService, batchRepository, storage, new DeltaMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                mock(PlatformTransactionManager.class), tempDir.toString(), 10_000_000L, 60,
                maxAttempts, 1800);
    }

    @Test
    void enqueueCreatesOnePendingArtifactPerAggregatedTableAndIsIdempotent() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        ChangelogSegment first = segment(siteId, batchId, "first", 1, 10, Map.of(
                "orders", new TableChangeStats(7, 2, 1),
                "items", new TableChangeStats(4, 0, 0)));
        ChangelogSegment second = segment(siteId, batchId, "second", 11, 20, Map.of(
                "orders", new TableChangeStats(5, 1, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(first, second));
        when(artifactRepository.insertPendingIfAbsent(any(), eq(batchId), eq(siteId), any(), any()))
                .thenReturn(1);

        assertEquals(2, service.enqueueBatch(batchId));

        ArgumentCaptor<String> tables = ArgumentCaptor.forClass(String.class);
        verify(artifactRepository, times(2))
                .insertPendingIfAbsent(any(), eq(batchId), eq(siteId), tables.capture(), any());
        assertEquals(List.of("items", "orders"), tables.getAllValues().stream().sorted().toList());

        // A replayed completion event re-runs the insert; the unique index absorbs it (0 rows).
        when(artifactRepository.insertPendingIfAbsent(any(), eq(batchId), eq(siteId), any(), any()))
                .thenReturn(0);
        assertEquals(0, service.enqueueBatch(batchId));
    }

    @Test
    void onDemandBackfillRefusesABatchWhoseSessionIsStillRunning() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        // A continuous session seals non-provisional segments while its batch stays IN_PROGRESS;
        // enqueueing then would publish a truncated artifact that is never rebuilt.
        ChangelogSegment sealed = segment(siteId, batchId, "sealed", 1, 10,
                Map.of("orders", new TableChangeStats(10, 0, 0)));
        Batch running = batch(BatchStatus.IN_PROGRESS);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(sealed));
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(running));

        assertEquals(0, service.enqueueBatchForSite(siteId, batchId));

        verify(artifactRepository, never()).insertPendingIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void onDemandBackfillEnqueuesAFinishedBatchOfTheAskingSite() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        ChangelogSegment sealed = segment(siteId, batchId, "sealed", 1, 10,
                Map.of("orders", new TableChangeStats(10, 0, 0)));
        Batch finished = batch(BatchStatus.COMPLETED);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(sealed));
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(finished));
        when(artifactRepository.insertPendingIfAbsent(any(), eq(batchId), eq(siteId), any(), any()))
                .thenReturn(1);

        assertEquals(1, service.enqueueBatchForSite(siteId, batchId));

        assertEquals(0, service.enqueueBatchForSite(UUID.randomUUID(), batchId),
                "a batch of another site is not enqueued");
    }

    @Test
    void finalizesOrderedSegmentsToOneReadyArtifactAndCleansTempFile() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment first = segment(siteId, batchId, "first", 1, 2,
                Map.of("orders", new TableChangeStats(2, 0, 0)));
        ChangelogSegment second = segment(siteId, batchId, "second", 3, 4,
                Map.of("orders", new TableChangeStats(1, 1, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(first, second));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("first", record(1, Op.INSERT), record(2, Op.INSERT));
        stream("second", record(3, Op.INSERT), record(4, Op.UPDATE));
        when(storage.uploadBatchParquet(eq(siteId), eq(batchId), eq("orders"), any(Path.class)))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        assertEquals(4, artifact.getRowCount());
        assertEquals("egress/orders.parquet", artifact.getS3Key());
        // One replay in segment order: the table declares no decimal column, so the precision
        // scan pass is skipped rather than re-downloading every segment.
        InOrder reads = inOrder(segmentService);
        reads.verify(segmentService).forEachRecord(eq("first"), any());
        reads.verify(segmentService).forEachRecord(eq("second"), any());
        verify(segmentService, times(1)).forEachRecord(eq("first"), any());
        verify(segmentService, times(1)).forEachRecord(eq("second"), any());
        try (var files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty(), "temp file deleted after success");
        }
    }

    @Test
    void commitsTheClaimBeforeTheBuildSoACrashStillSpendsAnAttempt() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        // The BUILDING claim is persisted before any segment is read or uploaded: a process that
        // dies mid-build leaves attemptCount already incremented instead of an endless retry.
        InOrder order = inOrder(artifactRepository, segmentService, storage);
        order.verify(artifactRepository).save(any(BatchParquetArtifact.class));
        order.verify(segmentService).forEachRecord(any(), any());
        order.verify(storage).uploadBatchParquet(any(), any(), any(), any());
        assertEquals(1, artifact.getAttemptCount());
    }

    @Test
    void uploadFailureStaysRetryableWhileAttemptsRemainAndCleansTempFile() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(eq(siteId), eq(batchId), eq("orders"), any(Path.class)))
                .thenThrow(new S3CheckpointStorage.CheckpointStorageException("upload failed", null));

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.FAILED, artifact.getStatus());
        assertTrue(artifact.isRetryable());
        assertTrue(artifact.getLastError().contains("upload failed"));
        try (var files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty(), "temp file deleted after failure");
        }
    }

    @Test
    void theLastAllowedAttemptAbandonsTheArtifactInsteadOfFailingItAgain() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of());

        assertTrue(newService(1).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.ABANDONED, artifact.getStatus());
        assertFalse(artifact.isRetryable());
        assertTrue(artifact.getLastError().contains("No declared schema"));
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any());
    }

    @Test
    void claimsWithTheConfiguredBackoffAndLeaseWindows() {
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertFalse(service.finalizeNext());

        verify(artifactRepository).findNextRetryable(any(LocalDateTime.class), eq(60), eq(1800), eq(1));
    }

    @Test
    void leavesTheRowAloneWhenSomethingElseMovedItWhileTheBuildRan() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any())).thenAnswer(invocation -> {
            // A site wipe removed the manifest row while this build was streaming from S3.
            when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.empty());
            return "egress/orders.parquet";
        });

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        verify(artifactRepository, times(1)).save(any(BatchParquetArtifact.class));
    }

    @Test
    void discardsItsOutcomeWhenTheClaimWasTakenOverMidBuild() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any())).thenAnswer(invocation -> {
            // Our lease lapsed and another worker reclaimed the row while we were still uploading.
            artifact.markBuilding();
            return "egress/orders.parquet";
        });

        assertTrue(service.finalizeNext());

        // The successor's attempt owns the row: publishing here would overwrite a live claim.
        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        assertEquals(2, artifact.getAttemptCount());
        assertNull(artifact.getS3Key());
    }

    @Test
    void refusesToPublishAnArtifactWhoseRowCountDisagreesWithTheSegmentStats() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        // The stats promise five rows; the replay yields one — a segment stream that ended early.
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 5,
                Map.of("orders", new TableChangeStats(5, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));

        assertTrue(service.finalizeNext());

        // Publishing it would advertise a silently truncated download as complete.
        assertEquals(BatchParquetArtifactStatus.FAILED, artifact.getStatus());
        assertTrue(artifact.getLastError().contains("does not match segment stats"));
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any());
    }

    /** A row the claim query will hand out, wired for the re-read that {@code publish} performs. */
    private BatchParquetArtifact claimable(UUID batchId, UUID siteId, String tableName) {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, tableName);
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(artifact));
        when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.of(artifact));
        return artifact;
    }

    private static Batch batch(BatchStatus status) {
        Batch batch = mock(Batch.class);
        when(batch.getStatus()).thenReturn(status);
        return batch;
    }

    private ChangelogSegment segment(UUID siteId, UUID batchId, String key, long first, long last,
                                     Map<String, TableChangeStats> stats) {
        return ChangelogSegment.create(siteId, batchId, first, last, last - first + 1,
                "hash-" + first, key, "DELTA", stats);
    }

    @SuppressWarnings("unchecked")
    private void stream(String key, ChangeRecord... records) {
        doAnswer(invocation -> {
            Consumer<ChangeRecord> consumer = invocation.getArgument(1);
            for (ChangeRecord record : records) {
                consumer.accept(record);
            }
            return null;
        }).when(segmentService).forEachRecord(eq(key), any(Consumer.class));
    }

    private static TableSchema schema() {
        return new TableSchema(List.of(new ColumnDefinition("id", "bigint", false)),
                List.of("id"), List.of());
    }

    private static ChangeRecord record(long seq, Op op) {
        Value id = Value.newBuilder().setIntValue(seq).build();
        return ChangeRecord.newBuilder().setTable("orders").setSeq(seq).setOp(op)
                .putKey("id", id).putData("id", id).build();
    }
}
