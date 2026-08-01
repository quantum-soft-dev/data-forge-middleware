package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

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
    void deletesTheThreeDatabaseAggregatesAtomicallyBeforeBestEffortObjectCleanup() throws Exception {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        BatchParquetArtifact building = BatchParquetArtifact.pending(batchId, siteId, "sales orders");
        building.markBuilding();
        when(batchRepository.existsById(batchId)).thenReturn(true);
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(building));
        when(storage.deleteObjects(List.of(building.expectedS3Key())))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        assertTrue(service.deleteBatch(batchId));

        InOrder order = inOrder(artifactRepository, segmentService, batchRepository, storage);
        order.verify(artifactRepository).findByBatchId(batchId);
        order.verify(artifactRepository).deleteByBatchId(batchId);
        order.verify(segmentService).deleteByBatchId(batchId);
        order.verify(batchRepository).deleteById(batchId);
        order.verify(storage).deleteObjects(List.of(building.expectedS3Key()));

        Method method = BatchDeletionService.class.getMethod("deleteBatch", UUID.class);
        assertThat(method.getAnnotation(Transactional.class))
                .describedAs("artifact, segment, and batch rows must roll back together")
                .isNotNull();
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
