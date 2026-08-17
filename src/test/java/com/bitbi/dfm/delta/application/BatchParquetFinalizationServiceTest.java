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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchParquetFinalizationServiceTest {

    private static final LocalDateTime CATALOG_WATERMARK = LocalDateTime.of(2026, 8, 14, 12, 0);

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
        lenient().when(artifactRepository.nextCatalogWatermark()).thenReturn(CATALOG_WATERMARK);
        service = newService(5);
    }

    private BatchParquetFinalizationService newService(int maxAttempts) {
        return newService(maxAttempts, 1800);
    }

    private BatchParquetFinalizationService newService(int maxAttempts, int leaseSeconds) {
        return newService(maxAttempts, leaseSeconds, 10_000_000L);
    }

    private BatchParquetFinalizationService newService(int maxAttempts, int leaseSeconds, long maxTempBytes) {
        return newService(maxAttempts, leaseSeconds, maxTempBytes, new SimpleMeterRegistry());
    }

    private BatchParquetFinalizationService newService(int maxAttempts, int leaseSeconds,
                                                       long maxTempBytes, SimpleMeterRegistry registry) {
        return newService(maxAttempts, leaseSeconds, maxTempBytes, registry,
                TestScratchLeases.unboundedBudget());
    }

    /** As above, with a scratch directory shared with other writers (issue #150). */
    private BatchParquetFinalizationService newService(int maxAttempts, int leaseSeconds,
                                                       long maxTempBytes, SimpleMeterRegistry registry,
                                                       ParquetScratchBudget scratchBudget) {
        return new BatchParquetFinalizationService(artifactRepository, segmentRepository,
                segmentService, schemaService, batchRepository, storage, new DeltaMetrics(registry),
                new DeltaParquetProperties(8L * 1024 * 1024), scratchBudget,
                mock(PlatformTransactionManager.class), tempDir.toString(), maxTempBytes, 60,
                maxAttempts, leaseSeconds);
    }

    private static long phaseCount(SimpleMeterRegistry registry, String phase) {
        return registry.get("delta.batch-parquet.duration").tag("phase", phase).timer().count();
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
    void enqueueDiscoversTablesByReplayingLegacySegmentsWithoutStats() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        ChangelogSegment legacy = segment(siteId, batchId, "legacy", 1, 2, null);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(legacy));
        stream("legacy", record("orders", 1, Op.INSERT), record("customers", 2, Op.UPDATE));
        when(artifactRepository.insertPendingIfAbsent(any(), eq(batchId), eq(siteId), any(), any()))
                .thenReturn(1);

        assertEquals(2, service.enqueueBatch(batchId));

        ArgumentCaptor<String> tables = ArgumentCaptor.forClass(String.class);
        verify(artifactRepository, times(2))
                .insertPendingIfAbsent(any(), eq(batchId), eq(siteId), tables.capture(), any());
        assertEquals(List.of("customers", "orders"), tables.getAllValues().stream().sorted().toList());
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
        when(storage.uploadBatchParquet(
                eq(siteId), eq(batchId), eq("orders"), any(UUID.class), any(Path.class)))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        assertEquals(4, artifact.getRowCount());
        assertEquals("egress/orders.parquet", artifact.getS3Key());
        assertEquals(1L, artifact.getFirstSeq());
        assertEquals(4L, artifact.getLastSeq());
        InOrder publishOrder = inOrder(artifactRepository);
        publishOrder.verify(artifactRepository).save(any(BatchParquetArtifact.class));
        publishOrder.verify(artifactRepository).lockCatalogPublish();
        publishOrder.verify(artifactRepository).findById(artifact.getId());
        publishOrder.verify(artifactRepository).nextCatalogWatermark();
        publishOrder.verify(artifactRepository).save(any(BatchParquetArtifact.class));
        assertEquals(CATALOG_WATERMARK, artifact.getReadyAt());
        // One replay in segment order: the table declares no decimal column, so the precision
        // scan pass is skipped rather than re-downloading every segment.
        InOrder reads = inOrder(segmentService);
        reads.verify(segmentService).forEachRecord(eq("first"), any(), any());
        reads.verify(segmentService).forEachRecord(eq("second"), any(), any());
        verify(segmentService, times(1)).forEachRecord(eq("first"), any(), any());
        verify(segmentService, times(1)).forEachRecord(eq("second"), any(), any());
        try (var files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty(), "temp file deleted after success");
        }
    }

    @Test
    void recordsWriteAndUploadPhasesOnASuccessfulBuild() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchParquetFinalizationService timed = newService(5, 1800, 10_000_000L, registry);
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(timed.finalizeNext());

        assertEquals(1L, phaseCount(registry, "write"));
        assertEquals(1L, phaseCount(registry, "upload"));
        assertEquals(0L, phaseCount(registry, "decimal_scan"));
        assertEquals(1L, phaseCount(registry, "total"));
    }

    @Test
    void recordsDecimalScanPhaseWhenAClaimedTableDeclaresDecimals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchParquetFinalizationService timed = newService(5, 1800, 10_000_000L, registry);
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        claimable(batchId, siteId, "payments");
        ChangelogSegment segment = segment(siteId, batchId, "money", 1, 1,
                Map.of("payments", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("payments", new TableSchema(
                List.of(new ColumnDefinition("id", "bigint", false),
                        new ColumnDefinition("amount", "numeric(7,2)", false)),
                List.of("id"), List.of())));
        Value id = Value.newBuilder().setIntValue(1).build();
        Value amount = Value.newBuilder().setDecimalValue("1.00").build();
        stream("money", ChangeRecord.newBuilder().setTable("payments").setSeq(1L).setOp(Op.INSERT)
                .putKey("id", id).putData("id", id).putData("amount", amount).build());
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/payments.parquet");

        assertTrue(timed.finalizeNext());

        assertEquals(1L, phaseCount(registry, "decimal_scan"));
        assertEquals(1L, phaseCount(registry, "write"));
        assertEquals(1L, phaseCount(registry, "upload"));
    }

    @Test
    void finalizesEveryRetryableTableInTheBatchFromOneReplay() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact customers = BatchParquetArtifact.pending(batchId, siteId, "customers");
        BatchParquetArtifact orders = BatchParquetArtifact.pending(batchId, siteId, "orders");
        claimableBatch(customers, orders);
        ChangelogSegment segment = segment(siteId, batchId, "mixed", 1, 4, Map.of(
                "customers", new TableChangeStats(2, 0, 0),
                "orders", new TableChangeStats(1, 0, 1)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of(
                "customers", schema(), "orders", schema()));
        stream("mixed", record("customers", 1, Op.INSERT), record("orders", 2, Op.INSERT),
                record("customers", 3, Op.INSERT), record("orders", 4, Op.DELETE));
        when(storage.uploadBatchParquet(
                eq(siteId), eq(batchId), eq("customers"), any(UUID.class), any(Path.class)))
                .thenReturn("egress/customers.parquet");
        when(storage.uploadBatchParquet(
                eq(siteId), eq(batchId), eq("orders"), any(UUID.class), any(Path.class)))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, customers.getStatus());
        assertEquals(BatchParquetArtifactStatus.READY, orders.getStatus());
        assertEquals(2, customers.getRowCount());
        assertEquals(2, orders.getRowCount());
        verify(segmentService, times(1)).forEachRecord(eq("mixed"), any(), any());
        verify(artifactRepository).tryLockBatch(batchId);
        verify(artifactRepository).findRetryableByBatchId(
                eq(batchId), any(LocalDateTime.class), eq(60), eq(1800), eq(5));
    }

    @Test
    void oneTableFailureDoesNotBlockItsBatchSibling() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact customers = BatchParquetArtifact.pending(batchId, siteId, "customers");
        BatchParquetArtifact missingSchema = BatchParquetArtifact.pending(batchId, siteId, "ghost");
        claimableBatch(customers, missingSchema);
        ChangelogSegment segment = segment(siteId, batchId, "mixed", 1, 2, Map.of(
                "customers", new TableChangeStats(1, 0, 0),
                "ghost", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("customers", schema()));
        stream("mixed", record("customers", 1, Op.INSERT), record("ghost", 2, Op.INSERT));
        when(storage.uploadBatchParquet(
                eq(siteId), eq(batchId), eq("customers"), any(UUID.class), any(Path.class)))
                .thenReturn("egress/customers.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, customers.getStatus());
        assertEquals(BatchParquetArtifactStatus.FAILED, missingSchema.getStatus());
        assertTrue(missingSchema.getLastError().contains("No declared schema"));
        verify(segmentService, times(1)).forEachRecord(eq("mixed"), any(), any());
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
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        // The BUILDING claim is persisted before any segment is read or uploaded: a process that
        // dies mid-build leaves attemptCount already incremented instead of an endless retry.
        InOrder order = inOrder(artifactRepository, segmentService, storage);
        order.verify(artifactRepository).save(any(BatchParquetArtifact.class));
        order.verify(segmentService).forEachRecord(any(), any(), any());
        order.verify(storage).uploadBatchParquet(any(), any(), any(), any(), any());
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
        when(storage.uploadBatchParquet(
                eq(siteId), eq(batchId), eq("orders"), any(UUID.class), any(Path.class)))
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
        assertEquals(1L, artifact.getFirstSeq(),
                "an abandon after a failed build must keep the table range from the segments");
        assertEquals(1L, artifact.getLastSeq());
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any(), any());
    }

    @Test
    void anArtifactRefusedByTheSharedScratchDirectoryIsRetriedRatherThanAbandoned() throws Exception {
        // Issue #150. The look-alike above is abandoned on its first attempt because it is
        // deterministically too big for its own ceiling — every retry would fail identically. This
        // one is the opposite: the artifact is ordinary and the directory was full of other
        // writers' files, so it takes the ordinary backoff and the download answers 409 rather
        // than a permanent 404.
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        ParquetScratchBudget full = TestScratchLeases.budgetOf(8L);

        assertTrue(newService(7, 1800, 10_000_000L, new SimpleMeterRegistry(), full).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.FAILED, artifact.getStatus());
        assertEquals(1, artifact.getAttemptCount());
        assertTrue(artifact.getLastError().contains("delta.parquet.max-scratch-bytes"),
                "the operator's lever must reach the manifest row: " + artifact.getLastError());
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any(), any());
        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty(), "the partial file is deleted");
        }
    }

    @Test
    void releasesTheDirectoryItReservedWhenTheBuildIsDone() throws Exception {
        // A lease outliving its file would shrink the directory for every later build until the
        // pod restarted. A budget of one reservation chunk is ample for a one-table build and is
        // exhausted by the second if the first one leaks.
        UUID siteId = UUID.randomUUID();
        ParquetScratchBudget narrow = TestScratchLeases.budgetOf(ParquetScratchBudget.CHUNK_BYTES);
        BatchParquetFinalizationService service = newService(7, 1800, 10_000_000L,
                new SimpleMeterRegistry(), narrow);

        for (int i = 0; i < 3; i++) {
            UUID batchId = UUID.randomUUID();
            BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
            ChangelogSegment segment = segment(siteId, batchId, "seg" + i, 1, 1,
                    Map.of("orders", new TableChangeStats(1, 0, 0)));
            when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
            when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
            stream("seg" + i, record(1, Op.INSERT));
            when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                    .thenReturn("egress/orders.parquet");

            assertTrue(service.finalizeNext());

            assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus(),
                    "build " + i + " must find the directory as it was left, was refused with: "
                            + artifact.getLastError());
        }
    }

    @Test
    void anOversizedArtifactIsAbandonedOnItsFirstDeterministicAttempt() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));

        assertTrue(newService(7, 1800, 8).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.ABANDONED, artifact.getStatus());
        assertEquals(1, artifact.getAttemptCount());
        assertTrue(artifact.getLastError().contains("exceeds temp-file limit"));
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any(), any());
        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty(), "partial oversized file is deleted");
        }
    }

    @Test
    void claimsWithTheConfiguredBackoffAndLeaseWindows() {
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertFalse(service.finalizeNext());

        verify(artifactRepository).findNextRetryable(
                any(LocalDateTime.class), eq(60), eq(1800), eq(5), eq(1));
    }

    @Test
    void keepsTheDrainAliveWhenAnotherWorkerHoldsTheBatchAdvisoryLock() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        when(artifactRepository.findNextRetryable(
                any(LocalDateTime.class), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(artifact));
        when(artifactRepository.tryLockBatch(batchId)).thenReturn(false);

        assertTrue(service.finalizeNext(),
                "contention means the queue is non-empty, so the drain must select again");

        verify(artifactRepository, never()).findRetryableByBatchId(
                any(), any(LocalDateTime.class), anyInt(), anyInt(), anyInt());
        verify(artifactRepository, never()).save(any(BatchParquetArtifact.class));
    }

    @Test
    void publishesEachBatchSiblingInAnIndependentTransaction() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact customers = BatchParquetArtifact.pending(batchId, siteId, "customers");
        BatchParquetArtifact orders = BatchParquetArtifact.pending(batchId, siteId, "orders");
        claimableBatch(customers, orders);
        ChangelogSegment segment = segment(siteId, batchId, "mixed", 1, 2, Map.of(
                "customers", new TableChangeStats(1, 0, 0),
                "orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of(
                "customers", schema(), "orders", schema()));
        stream("mixed", record("customers", 1, Op.INSERT), record("orders", 2, Op.INSERT));
        when(storage.uploadBatchParquet(
                eq(siteId), eq(batchId), any(), any(UUID.class), any(Path.class)))
                .thenAnswer(invocation -> "egress/" + invocation.getArgument(2) + ".parquet");
        doAnswer(invocation -> {
            BatchParquetArtifact saved = invocation.getArgument(0);
            if (saved.getTableName().equals("customers")
                    && saved.getStatus() == BatchParquetArtifactStatus.READY) {
                throw new IllegalStateException("simulated optimistic-lock conflict");
            }
            return saved;
        }).when(artifactRepository).save(any(BatchParquetArtifact.class));

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, orders.getStatus(),
                "one stale sibling must not roll back or skip another sibling's publication");
        verify(artifactRepository, times(2)).findById(any());
        verify(storage, never()).deleteBatchParquet("egress/customers.parquet");
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
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            // A site wipe removed the manifest row while this build was streaming from S3.
            when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.empty());
            return "egress/orders.parquet";
        });

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        verify(artifactRepository, times(1)).save(any(BatchParquetArtifact.class));
        // Nothing else can name that key now that the row is gone, so the uploader cleans up.
        verify(storage).deleteBatchParquet("egress/orders.parquet");
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
        String staleAttemptKey = "egress/site/batches/batch/attempts/old/orders.parquet";
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            // Our lease lapsed and another worker reclaimed the row while we were still uploading.
            artifact.markBuilding();
            return staleAttemptKey;
        });

        assertTrue(service.finalizeNext());

        // The successor's attempt owns the row: publishing here would overwrite a live claim.
        assertEquals(BatchParquetArtifactStatus.BUILDING, artifact.getStatus());
        assertEquals(2, artifact.getAttemptCount());
        assertNull(artifact.getS3Key());
        verify(storage).deleteBatchParquet(staleAttemptKey);
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
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any(), any());
    }

    @Test
    void publishesACompleteReplayWhenLegacySegmentsHaveNoExpectedRowCount() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment legacy = segment(siteId, batchId, "legacy", 1, 1, null);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(legacy));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("legacy", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        assertEquals(1, artifact.getRowCount());
        assertEquals(1L, artifact.getFirstSeq(), "legacy rows fall back to the batch-wide published range");
        assertEquals(1L, artifact.getLastSeq());
    }

    @Test
    void recordsTheSeqRangeOfSegmentsThatMentionTheTable() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment itemsOnly = segment(siteId, batchId, "items", 1, 10,
                Map.of("items", new TableChangeStats(10, 0, 0)));
        ChangelogSegment ordersTail = segment(siteId, batchId, "orders", 11, 20,
                Map.of("orders", new TableChangeStats(2, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId))
                .thenReturn(List.of(itemsOnly, ordersTail));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("items");
        stream("orders", record(11, Op.INSERT), record(12, Op.INSERT));
        when(storage.uploadBatchParquet(eq(siteId), eq(batchId), eq("orders"), any(UUID.class), any(Path.class)))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        assertEquals(11L, artifact.getFirstSeq());
        assertEquals(20L, artifact.getLastSeq());
    }

    @Test
    void usesBatchWideSeqRangeWhenAnyPublishedSegmentHasNoStats() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment named = segment(siteId, batchId, "named", 1, 10,
                Map.of("orders", new TableChangeStats(2, 0, 0)));
        ChangelogSegment legacyTail = segment(siteId, batchId, "legacy", 11, 20, null);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId))
                .thenReturn(List.of(named, legacyTail));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("named", record(1, Op.INSERT), record(2, Op.INSERT));
        stream("legacy", record(11, Op.INSERT));
        when(storage.uploadBatchParquet(eq(siteId), eq(batchId), eq("orders"), any(UUID.class), any(Path.class)))
                .thenReturn("egress/orders.parquet");

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        assertEquals(1L, artifact.getFirstSeq(),
                "a null-stats tail must not shrink lastSeq below the batch-wide published range");
        assertEquals(20L, artifact.getLastSeq());
    }

    @Test
    void renewsItsLeaseWhileTheBuildIsStillRunning() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        CountDownLatch renewed = new CountDownLatch(1);
        // The token only exists once the claim is taken, so it cannot be matched on up front.
        when(artifactRepository.touchClaim(eq(artifact.getId()), any(), any()))
                .thenAnswer(invocation -> {
                    renewed.countDown();
                    return 1;
                });
        // The upload holds the build open until a renewal lands: without one, lease-seconds would
        // be a hard ceiling on build time and a long build would be reclaimed mid-flight.
        AtomicReference<UUID> tokenDuringBuild = new AtomicReference<>();
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            tokenDuringBuild.set(artifact.getClaimToken());
            assertTrue(renewed.await(10, TimeUnit.SECONDS), "the build lease was never renewed");
            return "egress/orders.parquet";
        });

        // lease-seconds 3 → the owner touches the row every second.
        assertTrue(newService(7, 3).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, artifact.getStatus());
        ArgumentCaptor<UUID> renewedToken = ArgumentCaptor.forClass(UUID.class);
        verify(artifactRepository, atLeastOnce())
                .touchClaim(eq(artifact.getId()), renewedToken.capture(), any());
        assertEquals(tokenDuringBuild.get(), renewedToken.getValue(),
                "the renewal must carry the claim's own token, or it would extend a stranger's lease");
    }

    @Test
    void renewsEverySiblingTogetherUnderTheBatchAdvisoryLock() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact customers = BatchParquetArtifact.pending(batchId, siteId, "customers");
        BatchParquetArtifact orders = BatchParquetArtifact.pending(batchId, siteId, "orders");
        claimableBatch(customers, orders);
        ChangelogSegment segment = segment(siteId, batchId, "mixed", 1, 2, Map.of(
                "customers", new TableChangeStats(1, 0, 0),
                "orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of(
                "customers", schema(), "orders", schema()));
        stream("mixed", record("customers", 1, Op.INSERT), record("orders", 2, Op.INSERT));
        CountDownLatch renewed = new CountDownLatch(2);
        when(artifactRepository.touchClaim(any(), any(), any())).thenAnswer(invocation -> {
            renewed.countDown();
            return 1;
        });
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertTrue(renewed.await(10, TimeUnit.SECONDS), "both sibling leases were not renewed");
            return "egress/" + invocation.getArgument(2) + ".parquet";
        });

        assertTrue(newService(7, 3).finalizeNext());

        verify(artifactRepository, atLeastOnce()).touchClaim(eq(customers.getId()), any(), any());
        verify(artifactRepository, atLeastOnce()).touchClaim(eq(orders.getId()), any(), any());
        verify(artifactRepository, atLeast(2)).tryLockBatch(batchId);
    }

    @Test
    void keepsRenewingTheBatchUntilEverySiblingIsPublished() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact customers = BatchParquetArtifact.pending(batchId, siteId, "customers");
        BatchParquetArtifact orders = BatchParquetArtifact.pending(batchId, siteId, "orders");
        claimableBatch(customers, orders);
        ChangelogSegment segment = segment(siteId, batchId, "mixed", 1, 2, Map.of(
                "customers", new TableChangeStats(1, 0, 0),
                "orders", new TableChangeStats(1, 0, 0)));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of(
                "customers", schema(), "orders", schema()));
        stream("mixed", record("customers", 1, Op.INSERT), record("orders", 2, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                "egress/" + invocation.getArgument(2) + ".parquet");
        AtomicBoolean publishing = new AtomicBoolean();
        AtomicBoolean renewedDuringPublish = new AtomicBoolean();
        CountDownLatch renewal = new CountDownLatch(1);
        when(artifactRepository.touchClaim(any(), any(), any())).thenAnswer(invocation -> {
            if (publishing.get()) {
                renewedDuringPublish.set(true);
                renewal.countDown();
            }
            return 1;
        });
        doAnswer(invocation -> {
            BatchParquetArtifact saved = invocation.getArgument(0);
            if (saved.getTableName().equals("customers")
                    && saved.getStatus() == BatchParquetArtifactStatus.READY) {
                publishing.set(true);
                renewal.await(3, TimeUnit.SECONDS);
            }
            return saved;
        }).when(artifactRepository).save(any(BatchParquetArtifact.class));

        assertTrue(newService(7, 3).finalizeNext());

        assertTrue(renewedDuringPublish.get(),
                "lease renewal must continue through the independent publication transactions");
        assertEquals(BatchParquetArtifactStatus.READY, orders.getStatus());
    }

    @Test
    void idlePollTakesNeitherTheCatalogLockNorTheWatermark() {
        when(artifactRepository.hasSpentExpiredClaims(any(LocalDateTime.class), eq(1800), eq(7)))
                .thenReturn(false);

        assertFalse(newService(7).finalizeNext());

        verify(artifactRepository).hasSpentExpiredClaims(any(LocalDateTime.class), eq(1800), eq(7));
        verify(artifactRepository, never()).lockCatalogPublish();
        verify(artifactRepository, never()).nextCatalogWatermark();
        verify(artifactRepository, never()).abandonExpiredClaims(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt(), any());
    }

    @Test
    void aPollThatFindsWorkStillLeavesTheWatermarkToThePublicationItself() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact retryable = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(artifactRepository.hasSpentExpiredClaims(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(false);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(newService(7).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, retryable.getStatus());
        verify(artifactRepository, never()).abandonExpiredClaims(
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt(), any());
        // publish() owns the one lock/watermark pair a productive poll is allowed to pay for.
        verify(artifactRepository, times(1)).lockCatalogPublish();
        verify(artifactRepository, times(1)).nextCatalogWatermark();
    }

    @Test
    void bulkSettlesSpentClaimsWithoutHidingRetryableWorkBehindThem() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact retryable = claimable(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(artifactRepository.hasSpentExpiredClaims(any(LocalDateTime.class), eq(1800), eq(7)))
                .thenReturn(true);
        when(artifactRepository.abandonExpiredClaims(any(LocalDateTime.class), any(LocalDateTime.class),
                eq(1800), eq(7), any()))
                .thenReturn(17);
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(newService(7).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, retryable.getStatus());
        InOrder settleOrder = inOrder(artifactRepository);
        // The probe decides; the lock and the watermark are only paid for once it says yes.
        settleOrder.verify(artifactRepository).hasSpentExpiredClaims(
                any(LocalDateTime.class), eq(1800), eq(7));
        settleOrder.verify(artifactRepository).lockCatalogPublish();
        settleOrder.verify(artifactRepository).nextCatalogWatermark();
        settleOrder.verify(artifactRepository).abandonExpiredClaims(
                any(LocalDateTime.class), eq(CATALOG_WATERMARK), eq(1800), eq(7), any());
    }

    @Test
    void reclaimsAStrandedClaimWhileItStillHasAttemptsLeft() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact stranded = BatchParquetArtifact.pending(batchId, siteId, "orders");
        stranded.markBuilding();
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(stranded));
        when(artifactRepository.tryLockBatch(batchId)).thenReturn(true);
        when(artifactRepository.findRetryableByBatchId(
                eq(batchId), any(LocalDateTime.class), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(stranded));
        when(artifactRepository.findById(stranded.getId())).thenReturn(Optional.of(stranded));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(any(), any(), any(), any(), any()))
                .thenReturn("egress/orders.parquet");

        assertTrue(newService(7).finalizeNext());

        assertEquals(BatchParquetArtifactStatus.READY, stranded.getStatus());
        assertEquals(2, stranded.getAttemptCount(), "the lost attempt still counts against the cap");
    }

    /** A row the claim query will hand out, wired for the re-read that {@code publish} performs. */
    private BatchParquetArtifact claimable(UUID batchId, UUID siteId, String tableName) {
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, tableName);
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(artifact));
        when(artifactRepository.tryLockBatch(batchId)).thenReturn(true);
        when(artifactRepository.findRetryableByBatchId(
                eq(batchId), any(LocalDateTime.class), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(artifact));
        when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.of(artifact));
        return artifact;
    }

    private void claimableBatch(BatchParquetArtifact... artifacts) {
        UUID batchId = artifacts[0].getBatchId();
        when(artifactRepository.findNextRetryable(
                any(LocalDateTime.class), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(artifacts[0]));
        when(artifactRepository.tryLockBatch(batchId)).thenReturn(true);
        when(artifactRepository.findRetryableByBatchId(
                eq(batchId), any(LocalDateTime.class), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(artifacts));
        for (BatchParquetArtifact artifact : artifacts) {
            when(artifactRepository.findById(artifact.getId())).thenReturn(Optional.of(artifact));
        }
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
        doAnswer(invocation -> {
            Consumer<ChangeRecord> consumer = invocation.getArgument(1);
            for (ChangeRecord record : records) {
                consumer.accept(record);
            }
            return null;
        }).when(segmentService).forEachRecord(eq(key), any(Consumer.class), any());
    }

    private static TableSchema schema() {
        return new TableSchema(List.of(new ColumnDefinition("id", "bigint", false)),
                List.of("id"), List.of());
    }

    private static ChangeRecord record(long seq, Op op) {
        return record("orders", seq, op);
    }

    private static ChangeRecord record(String table, long seq, Op op) {
        Value id = Value.newBuilder().setIntValue(seq).build();
        return ChangeRecord.newBuilder().setTable(table).setSeq(seq).setOp(op)
                .putKey("id", id).putData("id", id).build();
    }
}
