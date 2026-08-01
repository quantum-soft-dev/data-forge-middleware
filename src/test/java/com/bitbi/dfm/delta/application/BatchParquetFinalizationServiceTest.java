package com.bitbi.dfm.delta.application;

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
    private final S3CheckpointStorage storage = mock(S3CheckpointStorage.class);
    private BatchParquetFinalizationService service;

    @BeforeEach
    void setUp() {
        service = new BatchParquetFinalizationService(artifactRepository, segmentRepository,
                segmentService, schemaService, storage, tempDir.toString(), 10_000_000L, 60, 5);
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
        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(eq(siteId), eq(batchId), any()))
                .thenReturn(Optional.empty());

        assertEquals(2, service.enqueueBatch(batchId));

        ArgumentCaptor<BatchParquetArtifact> saved = ArgumentCaptor.forClass(BatchParquetArtifact.class);
        verify(artifactRepository, times(2)).save(saved.capture());
        assertEquals(List.of("items", "orders"), saved.getAllValues().stream()
                .map(BatchParquetArtifact::getTableName).sorted().toList());
        assertTrue(saved.getAllValues().stream()
                .allMatch(artifact -> artifact.getStatus() == BatchParquetArtifactStatus.PENDING));

        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(eq(siteId), eq(batchId), any()))
                .thenReturn(Optional.of(saved.getValue()));
        assertEquals(0, service.enqueueBatch(batchId));
        verify(artifactRepository, times(2)).save(any());
    }

    @Test
    void finalizesOrderedSegmentsToOneReadyArtifactAndCleansTempFile() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        ChangelogSegment first = segment(siteId, batchId, "first", 1, 2,
                Map.of("orders", new TableChangeStats(2, 0, 0)));
        ChangelogSegment second = segment(siteId, batchId, "second", 3, 4,
                Map.of("orders", new TableChangeStats(1, 1, 0)));
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), eq(1)))
                .thenReturn(List.of(artifact));
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
    void uploadFailureMarksOnlyThatTableFailedAndCleansTempFile() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of(artifact));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of("orders", schema()));
        stream("only", record(1, Op.INSERT));
        when(storage.uploadBatchParquet(eq(siteId), eq(batchId), eq("orders"), any(Path.class)))
                .thenThrow(new S3CheckpointStorage.CheckpointStorageException("upload failed", null));

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.FAILED, artifact.getStatus());
        assertTrue(artifact.getLastError().contains("upload failed"));
        try (var files = Files.list(tempDir)) {
            assertTrue(files.findAny().isEmpty(), "temp file deleted after failure");
        }
    }

    @Test
    void missingSchemaFailsOneArtifactWithoutAttemptingUpload() {
        UUID siteId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        ChangelogSegment segment = segment(siteId, batchId, "only", 1, 1,
                Map.of("orders", new TableChangeStats(1, 0, 0)));
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of(artifact));
        when(segmentRepository.findByBatchIdOrderByFirstSeq(batchId)).thenReturn(List.of(segment));
        when(schemaService.getTableSchemas(siteId)).thenReturn(Map.of());

        assertTrue(service.finalizeNext());

        assertEquals(BatchParquetArtifactStatus.FAILED, artifact.getStatus());
        verify(storage, never()).uploadBatchParquet(any(), any(), any(), any());
    }

    @Test
    void claimsWorkWithTheConfiguredAttemptCeilingSoDeadArtifactsAreNeverRetried() {
        when(artifactRepository.findNextRetryable(any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertFalse(service.finalizeNext());

        verify(artifactRepository).findNextRetryable(any(LocalDateTime.class), eq(5), eq(1));
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
