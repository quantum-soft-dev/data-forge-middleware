package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.application.CsvFileQueryService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvFileQueryServiceTest {

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

    private CsvFileQueryService service;
    private UUID accountId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(meterRegistry.counter(any(String.class), any(String[].class)))
                .thenReturn(counter);
        service = new CsvFileQueryService(
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
    void shouldListCheckpointSnapshots() {
        allowOwnedSite();
        Checkpoint checkpoint = checkpoint("customers", "checkpoints/customers.csv.gz");
        when(checkpointRepository.findBySiteId(siteId)).thenReturn(List.of(checkpoint));
        when(checkpointStorage.contentLength(checkpoint.getS3KeyCsv())).thenReturn(128L);

        List<FileDto> result = service.listFiles(accountId, siteId);

        assertThat(result).singleElement().satisfies(file -> {
            assertThat(file.fileName()).isEqualTo("customers.csv.gz");
            assertThat(file.fileSize()).isEqualTo(128L);
        });
    }

    @Test
    void shouldIgnoreCheckpointsWithoutCsvSnapshot() {
        allowOwnedSite();
        when(checkpointRepository.findBySiteId(siteId))
                .thenReturn(List.of(Checkpoint.create(siteId, "customers", 10L, 2L)));

        assertThat(service.listFiles(accountId, siteId)).isEmpty();
    }

    @Test
    void shouldListHistoricalUploadsWhenNoCheckpointCsvExists() {
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
    void shouldDownloadCheckpointSnapshot() throws Exception {
        allowOwnedSite();
        Checkpoint checkpoint = checkpoint("customers", "checkpoints/customers.csv.gz");
        byte[] content = "gzip-content".getBytes();
        when(checkpointRepository.findBySiteIdAndTableName(siteId, "customers"))
                .thenReturn(Optional.of(checkpoint));
        when(checkpointStorage.open(checkpoint.getS3KeyCsv()))
                .thenReturn(new S3CheckpointStorage.CheckpointObject(
                        new ByteArrayInputStream(content), content.length));

        CsvFileQueryService.FileDownloadResult result =
                service.downloadFile(accountId, siteId, "customers.csv.gz");

        assertThat(result.fileName()).isEqualTo("customers.csv.gz");
        assertThat(result.fileSize()).isEqualTo(content.length);
        assertThat(result.contentType()).isEqualTo("application/gzip");
        assertThat(result.inputStream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void shouldRejectMissingCheckpointSnapshot() {
        allowOwnedSite();
        when(checkpointRepository.findBySiteIdAndTableName(siteId, "missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadFile(accountId, siteId, "missing.csv.gz"))
                .isInstanceOf(CsvFileQueryService.FileNotFoundException.class);
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
        checkpoint.attachCsv(s3Key);
        return checkpoint;
    }
}
