package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService.PresignedUrlResult;
import com.bitbi.dfm.delta.application.DeltaCheckpointQueryService.PresignedDownload;
import com.bitbi.dfm.delta.application.DeltaSegmentParquetQueryService.BatchParquetNotReadyException;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeltaSegmentParquetQueryServiceTest {

    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();

    private final BatchParquetArtifactRepository artifactRepository =
            mock(BatchParquetArtifactRepository.class);
    private final BatchParquetFinalizationService finalizationService =
            mock(BatchParquetFinalizationService.class);
    private final BatchParquetFinalizationWorker finalizationWorker =
            mock(BatchParquetFinalizationWorker.class);
    private final S3PresignedUrlService presignedUrlService = mock(S3PresignedUrlService.class);

    private final DeltaSegmentParquetQueryService service = new DeltaSegmentParquetQueryService(
            artifactRepository, finalizationService, finalizationWorker, presignedUrlService);

    @Test
    void presignsAReadyArtifact() {
        BatchParquetArtifact ready = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        ready.markBuilding();
        ready.markReady("egress/orders.parquet", 3, 100, "abc");
        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, "orders"))
                .thenReturn(Optional.of(ready));
        when(presignedUrlService.generatePresignedUrl(any(), any()))
                .thenReturn(new PresignedUrlResult("https://s3/orders", Instant.EPOCH));

        PresignedDownload download = service.presignBatchTableParquet(SITE_ID, BATCH_ID, "orders")
                .orElseThrow();

        assertEquals("orders_batch-" + BATCH_ID + ".parquet", download.fileName());
        verify(finalizationService, never()).enqueueBatchForSite(any(), any());
    }

    @Test
    void backfillsABatchThatPredatesTheManifestAndReportsItAsFinalizing() {
        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, "orders"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders")));
        when(finalizationService.enqueueBatchForSite(SITE_ID, BATCH_ID)).thenReturn(1);

        assertThrows(BatchParquetNotReadyException.class,
                () -> service.presignBatchTableParquet(SITE_ID, BATCH_ID, "orders"));

        verify(finalizationService).enqueueBatchForSite(SITE_ID, BATCH_ID);
        verify(finalizationWorker).wake();
    }

    @Test
    void returnsEmptyWhenTheBatchHasNothingToBackfill() {
        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, "orders"))
                .thenReturn(Optional.empty());
        when(finalizationService.enqueueBatchForSite(SITE_ID, BATCH_ID)).thenReturn(0);

        assertTrue(service.presignBatchTableParquet(SITE_ID, BATCH_ID, "orders").isEmpty());

        verify(finalizationWorker, never()).wake();
    }

    @Test
    void reportsAnAbandonedArtifactAsAbsentWithoutReenqueueing() {
        BatchParquetArtifact abandoned = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        abandoned.markBuilding();
        abandoned.markAbandoned("No declared schema for table orders");
        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, "orders"))
                .thenReturn(Optional.of(abandoned));

        assertTrue(service.presignBatchTableParquet(SITE_ID, BATCH_ID, "orders").isEmpty());

        verify(finalizationService, never()).enqueueBatchForSite(any(), any());
    }

    @Test
    void reportsAFailureThatStillHasAttemptsAsFinalizingRatherThanMissing() {
        BatchParquetArtifact failed = BatchParquetArtifact.pending(BATCH_ID, SITE_ID, "orders");
        failed.markBuilding();
        failed.markFailed("Failed to store unified batch Parquet");
        when(artifactRepository.findBySiteIdAndBatchIdAndTableName(SITE_ID, BATCH_ID, "orders"))
                .thenReturn(Optional.of(failed));

        assertThrows(BatchParquetNotReadyException.class,
                () -> service.presignBatchTableParquet(SITE_ID, BATCH_ID, "orders"));
    }
}
