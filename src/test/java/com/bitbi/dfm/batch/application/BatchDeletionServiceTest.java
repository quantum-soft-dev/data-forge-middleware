package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchDeletionServiceTest {

    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final ChangelogSegmentService segmentService = mock(ChangelogSegmentService.class);
    private final com.bitbi.dfm.delta.domain.ChangelogSegmentRepository segmentRepository =
            mock(com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.class);
    private final BatchParquetArtifactRepository artifactRepository =
            mock(BatchParquetArtifactRepository.class);
    private final S3FileStorageService storage = mock(S3FileStorageService.class);
    private final BatchDeletionService service = new BatchDeletionService(
            batchRepository, segmentService, segmentRepository, artifactRepository, storage);

    {
        // Every delete reaches the #212 pending-work count; no pending work unless a test says so.
        when(segmentRepository.countPendingQueueWorkByBatchId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(pendingWork(0, 0));
    }

    private static com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PendingQueueWork pendingWork(
            long pluginSql, long egress) {
        return new com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PendingQueueWork() {
            @Override
            public long getPendingPluginSql() {
                return pluginSql;
            }

            @Override
            public long getPendingEgress() {
                return egress;
            }
        };
    }

    @Test
    void deletesTheThreeDatabaseAggregatesAtomicallyThenCleansObjectsAfterCommit() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getSiteId()).thenReturn(siteId);
        BatchParquetArtifact building = BatchParquetArtifact.pending(batchId, siteId, "sales orders");
        building.markBuilding();
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(building));
        when(segmentService.deleteMetadataByBatchId(batchId))
                .thenReturn(List.of("delta/site/segments/1.pb.gz"));
        String prefix = S3CheckpointStorage.batchParquetPrefix(siteId, batchId);
        String orphanKey = prefix + "attempts/dead/sales%20orders.parquet";
        when(storage.listAllKeys(prefix)).thenReturn(List.of(orphanKey));
        List<String> objectKeys = List.of(
                building.expectedS3Key(), "delta/site/segments/1.pb.gz", orphanKey);
        when(storage.deleteObjects(objectKeys))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.deleteBatch(batchId));

            verify(storage, never()).deleteObjects(objectKeys);
            verify(storage, never()).listAllKeys(prefix);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        InOrder order = inOrder(artifactRepository, segmentService, batchRepository, storage);
        order.verify(artifactRepository).findByBatchId(batchId);
        order.verify(artifactRepository).deleteByBatchId(batchId);
        order.verify(segmentService).deleteMetadataByBatchId(batchId);
        order.verify(batchRepository).deleteById(batchId);
        order.verify(storage).deleteObjects(objectKeys);

        Method method = BatchDeletionService.class.getMethod("deleteBatch", UUID.class);
        assertThat(method.getAnnotation(Transactional.class))
                .describedAs("artifact, segment, and batch rows must roll back together")
                .isNotNull();
    }

    @Test
    void rollbackNeverDeletesObjectsWhoseDatabaseRowsAreRestored() {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getSiteId()).thenReturn(siteId);
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(artifact));
        when(segmentService.deleteMetadataByBatchId(batchId))
                .thenReturn(List.of("delta/site/segments/1.pb.gz"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.deleteBatch(batchId));
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(storage, never()).deleteObjects(org.mockito.ArgumentMatchers.anyList());
        verify(storage, never()).listAllKeys(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unknownBatchTouchesNoDependentAggregate() {
        UUID batchId = UUID.randomUUID();
        when(batchRepository.findById(batchId)).thenReturn(Optional.empty());

        assertFalse(service.deleteBatch(batchId));

        verify(artifactRepository, never()).deleteByBatchId(batchId);
        verify(segmentService, never()).deleteByBatchId(batchId);
        verify(storage, never()).deleteObjects(List.of());
    }

    @Test
    void truncatedPrefixListingFallsBackToRecordedExactKeysOnly() {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getSiteId()).thenReturn(siteId);
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        artifact.markBuilding();
        String recordedKey = artifact.expectedS3Key();
        artifact.markReady(recordedKey, 1, 4, "hash");
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(artifact));
        when(segmentService.deleteMetadataByBatchId(batchId)).thenReturn(List.of());
        String prefix = S3CheckpointStorage.batchParquetPrefix(siteId, batchId);
        when(storage.listAllKeys(prefix))
                .thenThrow(new S3FileStorageService.FileStorageException("list truncated"));
        when(storage.deleteObjects(List.of(recordedKey)))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        assertTrue(service.deleteBatch(batchId));

        verify(storage).deleteObjects(List.of(recordedKey));
    }

    @Test
    void warnsWhenAnOperatorDeleteDestroysPendingQueueWork() {
        // #212: an operator delete is a legitimate ending of retention's hold-back, but the
        // override must be informed. Logged only — the deleted-pending meter is reserved for the
        // scheduled batch-retention horizon.
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Batch batch = mock(Batch.class);
        when(batch.getSiteId()).thenReturn(siteId);
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of());
        when(segmentService.deleteMetadataByBatchId(batchId)).thenReturn(List.of());
        when(storage.listAllKeys(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(storage.deleteObjects(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(0, List.of()));
        when(segmentRepository.countPendingQueueWorkByBatchId(batchId))
                .thenReturn(pendingWork(3, 0));

        try (com.bitbi.dfm.util.LogCapture capture =
                     com.bitbi.dfm.util.LogCapture.attachTo(BatchDeletionService.class)) {
            service.deleteBatch(batchId);

            org.junit.jupiter.api.Assertions.assertTrue(
                    capture.messagesAt(ch.qos.logback.classic.Level.WARN).stream()
                            .anyMatch(message -> message.contains("pending queue work")
                                    && message.contains("3 segment(s) awaiting plugin SQL")),
                    "the override must be informed of the work it destroys");
        }
    }
}
