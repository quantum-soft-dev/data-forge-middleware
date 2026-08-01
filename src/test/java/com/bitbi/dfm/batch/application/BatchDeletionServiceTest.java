package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.List;
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
    private final BatchParquetArtifactRepository artifactRepository =
            mock(BatchParquetArtifactRepository.class);
    private final S3FileStorageService storage = mock(S3FileStorageService.class);
    private final BatchDeletionService service = new BatchDeletionService(
            batchRepository, segmentService, artifactRepository, storage);

    @Test
    void deletesTheThreeDatabaseAggregatesAtomicallyThenCleansObjectsAfterCommit() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        BatchParquetArtifact building = BatchParquetArtifact.pending(batchId, siteId, "sales orders");
        building.markBuilding();
        when(batchRepository.existsById(batchId)).thenReturn(true);
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(building));
        when(segmentService.deleteMetadataByBatchId(batchId))
                .thenReturn(List.of("delta/site/segments/1.pb.gz"));
        List<String> objectKeys = List.of(building.expectedS3Key(), "delta/site/segments/1.pb.gz");
        when(storage.deleteObjects(objectKeys))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.deleteBatch(batchId));

            verify(storage, never()).deleteObjects(objectKeys);
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
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(
                batchId, UUID.randomUUID(), "orders");
        when(batchRepository.existsById(batchId)).thenReturn(true);
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
    }

    @Test
    void unknownBatchTouchesNoDependentAggregate() {
        UUID batchId = UUID.randomUUID();
        when(batchRepository.existsById(batchId)).thenReturn(false);

        assertFalse(service.deleteBatch(batchId));

        verify(artifactRepository, never()).deleteByBatchId(batchId);
        verify(segmentService, never()).deleteByBatchId(batchId);
        verify(storage, never()).deleteObjects(List.of());
    }
}
