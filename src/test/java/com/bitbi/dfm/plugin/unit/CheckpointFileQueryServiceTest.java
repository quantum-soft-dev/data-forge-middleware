package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.application.CheckpointFileQueryService;
import com.bitbi.dfm.plugin.presentation.dto.FileDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #113 — the Bit BI files API serves the checkpoint as {@code <table>.parquet}.
 *
 * <p>The V2 checkpoint build no longer materializes a CSV snapshot, so the Parquet object is the
 * only reconstructed baseline there is. The historical-uploads fallback stays for V1-era sites,
 * but is now keyed on the site having no checkpoints at all rather than on the requested format
 * being missing — otherwise a V2 site whose Parquet is pending would silently serve stale
 * pre-Delta uploads as if they were its baseline.</p>
 */
@ExtendWith(MockitoExtension.class)
class CheckpointFileQueryServiceTest {

    @Mock
    private UploadedFileRepository uploadedFileRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private CheckpointRepository checkpointRepository;
    @Mock
    private S3CheckpointStorage checkpointStorage;
    @Mock
    private S3Client s3Client;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private Counter counter;

    private CheckpointFileQueryService service;
    private UUID accountId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(meterRegistry.counter(any(String.class), any(String[].class)))
                .thenReturn(counter);
        service = new CheckpointFileQueryService(
                uploadedFileRepository,
                siteRepository,
                checkpointRepository,
                checkpointStorage,
                s3Client,
                "test-bucket",
                meterRegistry);
        accountId = UUID.randomUUID();
        siteId = UUID.randomUUID();
    }

    @Test
    void shouldListCheckpointSnapshotsAsParquet() {
        allowOwnedSite();
        Checkpoint checkpoint = checkpoint("customers", "checkpoints/customers.parquet");
        when(checkpointRepository.findBySiteId(siteId)).thenReturn(List.of(checkpoint));
        when(checkpointStorage.contentLength(checkpoint.getS3KeyParquet())).thenReturn(128L);

        List<FileDto> result = service.listFiles(accountId, siteId);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.fileName()).isEqualTo("customers.parquet");
            assertThat(file.fileSize()).isEqualTo(128L);
        });
    }

    @Test
    void shouldIgnoreCheckpointsWithoutParquetSnapshotAndNotFallBackToHistoricalUploads() {
        // A V2 site whose Parquet has not been built yet must answer "nothing to download",
        // not a list of pre-Delta uploads that no longer describe its current state.
        allowOwnedSite();
        when(checkpointRepository.findBySiteId(siteId))
                .thenReturn(List.of(Checkpoint.create(siteId, "customers", 10L, 2L)));

        assertThat(service.listFiles(accountId, siteId)).isEmpty();
        verifyNoInteractions(uploadedFileRepository);
    }

    @Test
    void shouldListHistoricalUploadsWhenTheSiteHasNoCheckpoints() {
        allowOwnedSite();
        UploadedFileRepository.LatestFileInfoWithS3Key historical =
                org.mockito.Mockito.mock(UploadedFileRepository.LatestFileInfoWithS3Key.class);
        when(historical.getOriginalFileName()).thenReturn("customers.csv");
        when(historical.getFileSize()).thenReturn(256L);
        when(historical.getUploadedAt()).thenReturn(Instant.parse("2026-07-01T00:00:00Z"));
        when(uploadedFileRepository.findLatestByOriginalFileNameForSite(siteId))
                .thenReturn(List.of(historical));

        List<FileDto> result = service.listFiles(accountId, siteId);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.fileName()).isEqualTo("customers.csv");
            assertThat(file.fileSize()).isEqualTo(256L);
        });
    }

    @Test
    void shouldDownloadCheckpointSnapshotAsParquet() throws Exception {
        allowOwnedSite();
        Checkpoint checkpoint = checkpoint("customers", "checkpoints/customers.parquet");
        byte[] content = "parquet-content".getBytes();
        when(checkpointRepository.findBySiteIdAndTableName(siteId, "customers"))
                .thenReturn(Optional.of(checkpoint));
        when(checkpointStorage.open(checkpoint.getS3KeyParquet()))
                .thenReturn(new S3CheckpointStorage.CheckpointObject(
                        new ByteArrayInputStream(content), content.length));

        CheckpointFileQueryService.FileDownloadResult result =
                service.downloadFile(accountId, siteId, "customers.parquet");

        assertThat(result.fileName()).isEqualTo("customers.parquet");
        assertThat(result.fileSize()).isEqualTo(content.length);
        assertThat(result.contentType()).isEqualTo("application/vnd.apache.parquet");
        assertThat(result.inputStream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void shouldRejectTheRetiredCsvFileName() {
        // The pre-#113 name must not silently resolve to the Parquet object: a client that still
        // asks for <table>.csv.gz would parse Parquet bytes as gzipped CSV.
        allowOwnedSite();
        when(checkpointRepository.findBySiteId(siteId))
                .thenReturn(List.of(checkpoint("customers", "checkpoints/customers.parquet")));

        assertThatThrownBy(() -> service.downloadFile(accountId, siteId, "customers.csv.gz"))
                .isInstanceOf(CheckpointFileQueryService.FileNotFoundException.class);
        verifyNoInteractions(uploadedFileRepository);
    }

    @Test
    void shouldNotServeAHistoricalUploadForACheckpointedSite() {
        // A site migrated from V1 still has its old uploaded rows. Falling back to them per file
        // name would answer the retired customers.csv.gz with pre-Delta bytes and a 200, so a
        // client that never noticed #113 would bootstrap from data years out of date.
        allowOwnedSite();
        when(checkpointRepository.findBySiteId(siteId))
                .thenReturn(List.of(checkpoint("customers", "checkpoints/customers.parquet")));

        assertThatThrownBy(() -> service.downloadFile(accountId, siteId, "legacy-upload.csv"))
                .isInstanceOf(CheckpointFileQueryService.FileNotFoundException.class);
        verifyNoInteractions(uploadedFileRepository);
    }

    @Test
    void shouldRejectMissingCheckpointSnapshot() {
        allowOwnedSite();
        when(checkpointRepository.findBySiteIdAndTableName(siteId, "missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadFile(accountId, siteId, "missing.parquet"))
                .isInstanceOf(CheckpointFileQueryService.FileNotFoundException.class);
    }

    @Test
    void shouldRejectSiteOwnedByAnotherAccount() {
        Site site = org.mockito.Mockito.mock(Site.class);
        when(site.getAccountId()).thenReturn(UUID.randomUUID());
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

        assertThatThrownBy(() -> service.listFiles(accountId, siteId))
                .isInstanceOf(SecurityException.class);
    }

    private void allowOwnedSite() {
        Site site = org.mockito.Mockito.mock(Site.class);
        when(site.getAccountId()).thenReturn(accountId);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
    }

    private Checkpoint checkpoint(String table, String s3Key) {
        Checkpoint checkpoint = Checkpoint.create(siteId, table, 10L, 2L);
        checkpoint.attachParquet(s3Key);
        return checkpoint;
    }
}
