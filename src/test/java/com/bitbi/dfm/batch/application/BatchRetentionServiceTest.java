package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService.DeleteObjectsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchRetentionService")
class BatchRetentionServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private com.bitbi.dfm.site.domain.SiteRepository siteRepository;

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    @Mock
    private PluginSqlGenerationRepository sqlGenerationRepository;

    @Mock
    private S3FileStorageService s3FileStorageService;

    @Mock
    private ChangelogSegmentService changelogSegmentService;

    @Mock
    private BatchParquetArtifactRepository artifactRepository;

    private BatchRetentionService service;

    private UUID siteId;
    private UUID accountId;
    private UUID batchId;

    @BeforeEach
    void setUp() {
        service = new BatchRetentionService(
                batchRepository,
                siteRepository,
                uploadedFileRepository,
                sqlGenerationRepository,
                s3FileStorageService,
                changelogSegmentService,
                artifactRepository
        );
        siteId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        batchId = UUID.randomUUID();
    }

    @Test
    @DisplayName("dryRun should not delete batches or S3 objects")
    void dryRun_shouldNotDelete() {
        Site site = mock(Site.class);
        Batch batch = mock(Batch.class);

        when(site.getId()).thenReturn(siteId);
        when(site.getRetentionDays()).thenReturn(45);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
        when(batchRepository.findCleanupCandidatesForSite(eq(siteId), any(), anyInt()))
                .thenReturn(List.of(batch));
        when(batch.getId()).thenReturn(batchId);

        UploadedFileRepository.FileKeySize fileKey = mock(UploadedFileRepository.FileKeySize.class);
        when(fileKey.getS3Key()).thenReturn("batch/file.csv");
        when(fileKey.getFileSize()).thenReturn(100L);
        when(uploadedFileRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of(fileKey));

        PluginSqlGenerationRepository.S3KeySize sqlKey = mock(PluginSqlGenerationRepository.S3KeySize.class);
        when(sqlKey.getS3Key()).thenReturn("plugins/sql.sql");
        when(sqlKey.getFileSizeBytes()).thenReturn(200L);
        when(sqlGenerationRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of(sqlKey));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(
                new BatchRetentionService.BatchCleanupRequest(siteId, null, null, null, 10, true)
        );

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.deletedBatches()).isEqualTo(0);
        assertThat(summary.deletedFiles()).isEqualTo(2);
        assertThat(summary.deletedBytes()).isEqualTo(300L);
        verify(s3FileStorageService, never()).deleteObjects(any());
        verify(batchRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("cleanup should delete batches when S3 deletion succeeds")
    void cleanup_shouldDeleteOnSuccess() {
        Site site = mock(Site.class);
        Batch batch = mock(Batch.class);

        when(site.getId()).thenReturn(siteId);
        when(site.getRetentionDays()).thenReturn(45);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
        when(batchRepository.findCleanupCandidatesForSite(eq(siteId), any(), anyInt()))
                .thenReturn(List.of(batch));
        when(batch.getId()).thenReturn(batchId);

        UploadedFileRepository.FileKeySize fileKey = mock(UploadedFileRepository.FileKeySize.class);
        when(fileKey.getS3Key()).thenReturn("batch/file.csv");
        when(fileKey.getFileSize()).thenReturn(100L);
        when(uploadedFileRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of(fileKey));
        when(sqlGenerationRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of());
        BatchParquetArtifact artifact = BatchParquetArtifact.pending(batchId, siteId, "orders");
        artifact.markBuilding();
        artifact.markReady("egress/batch/orders.parquet", 10, 400, "hash");
        when(artifactRepository.findByBatchId(batchId)).thenReturn(List.of(artifact));

        when(s3FileStorageService.deleteObjects(any())).thenReturn(new DeleteObjectsResult(1, List.of()));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(
                new BatchRetentionService.BatchCleanupRequest(siteId, null, null, LocalDateTime.now().minusDays(1), 10, false)
        );

        assertThat(summary.deletedBatches()).isEqualTo(1);
        verify(s3FileStorageService).deleteObjects(any());
        verify(sqlGenerationRepository).deleteByComparisonBatchId(batchId);
        verify(sqlGenerationRepository).deleteBySourceBatchId(batchId);
        // Delta v2 changelog segments must be removed before the batch so the batch_id FK does not block it.
        verify(changelogSegmentService).deleteByBatchId(batchId);
        verify(artifactRepository).deleteByBatchId(batchId);
        verify(batchRepository).deleteById(batchId);
        assertThat(summary.deletedBytes()).isEqualTo(500L);
        assertThat(summary.deletedFiles()).isEqualTo(2);
    }

    @Test
    @DisplayName("cleanup should still delete DB records even if S3 deletion returns errors (best-effort)")
    void cleanup_shouldDeleteDbWhenS3Errors() {
        Site site = mock(Site.class);
        Batch batch = mock(Batch.class);

        when(site.getId()).thenReturn(siteId);
        when(site.getRetentionDays()).thenReturn(45);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
        when(batchRepository.findCleanupCandidatesForSite(eq(siteId), any(), anyInt()))
                .thenReturn(List.of(batch));
        when(batch.getId()).thenReturn(batchId);

        UploadedFileRepository.FileKeySize fileKey = mock(UploadedFileRepository.FileKeySize.class);
        when(fileKey.getS3Key()).thenReturn("batch/file.csv");
        when(fileKey.getFileSize()).thenReturn(100L);
        when(uploadedFileRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of(fileKey));
        when(sqlGenerationRepository.findS3KeysByBatchId(batchId)).thenReturn(List.of());

        when(s3FileStorageService.deleteObjects(any()))
                .thenReturn(new DeleteObjectsResult(0, List.of("error")));

        BatchRetentionService.BatchCleanupSummary summary = service.runCleanup(
                new BatchRetentionService.BatchCleanupRequest(siteId, null, null, null, 10, false)
        );

        assertThat(summary.deletedBatches()).isEqualTo(1);
        assertThat(summary.errors()).isNotEmpty();
        verify(batchRepository).deleteById(batchId);
    }
}
