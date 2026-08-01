package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin batch delete has to name the unified Parquet objects (036) before the rows that hold their
 * keys are gone — and the rows go early, because {@code batches} cascades onto them.
 */
class BatchAdminControllerDeleteTest {

    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final UploadedFileRepository uploadedFileRepository = mock(UploadedFileRepository.class);
    private final BatchLifecycleService batchLifecycleService = mock(BatchLifecycleService.class);
    private final ChangelogSegmentService changelogSegmentService = mock(ChangelogSegmentService.class);
    private final BatchParquetArtifactRepository artifactRepository =
            mock(BatchParquetArtifactRepository.class);
    private final S3FileStorageService s3FileStorageService = mock(S3FileStorageService.class);

    private final BatchAdminController controller = new BatchAdminController(batchRepository,
            siteRepository, uploadedFileRepository, batchLifecycleService, changelogSegmentService,
            artifactRepository, s3FileStorageService);

    @Test
    void collectsArtifactKeysBeforeDeletingTheRowsAndThenRemovesTheObjects() {
        UUID batchId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        // Mid-build: s3_key is still null, but an attempt may already have uploaded the object.
        BatchParquetArtifact building = BatchParquetArtifact.pending(batchId, siteId, "orders");
        building.markBuilding();
        List<String> keys = List.of(S3CheckpointStorage.batchParquetKey(siteId, batchId, "orders"));
        when(batchRepository.existsById(batchId)).thenReturn(true);
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(building));
        when(s3FileStorageService.deleteObjects(keys))
                .thenReturn(new S3FileStorageService.DeleteObjectsResult(1, List.of()));

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteBatch(batchId).getStatusCode());

        // Reading the rows after either delete would return nothing — they are gone by then,
        // through this call or through the batches FK cascade — and every unified object would be
        // silently orphaned in S3 with nothing logged.
        InOrder order = inOrder(artifactRepository, batchRepository, s3FileStorageService);
        order.verify(artifactRepository).findByBatchId(batchId);
        order.verify(artifactRepository).deleteByBatchId(batchId);
        order.verify(batchRepository).deleteById(batchId);
        order.verify(s3FileStorageService).deleteObjects(keys);
    }

    @Test
    void answers404AndTouchesNothingWhenTheBatchIsUnknown() {
        UUID batchId = UUID.randomUUID();
        when(batchRepository.existsById(batchId)).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, controller.deleteBatch(batchId).getStatusCode());

        verify(artifactRepository, never()).deleteByBatchId(any());
        verify(s3FileStorageService, never()).deleteObjects(any());
    }
}
