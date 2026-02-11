package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.CsvFileQueryService;
import com.bitbi.dfm.plugin.presentation.dto.FileDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository.LatestFileInfoWithS3Key;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CsvFileQueryService.
 *
 * <p>Tests:
 * <ul>
 *   <li>listFiles - returns files for a site</li>
 *   <li>downloadFile - downloads file from S3</li>
 *   <li>site ownership validation</li>
 *   <li>content type determination</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CsvFileQueryService")
class CsvFileQueryServiceTest {

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private S3Client s3Client;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    private CsvFileQueryService service;

    private UUID accountId;
    private UUID siteId;
    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        service = new CsvFileQueryService(
                uploadedFileRepository,
                siteRepository,
                s3Client,
                BUCKET_NAME,
                meterRegistry
        );

        accountId = UUID.randomUUID();
        siteId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("listFiles()")
    class ListFilesMethod {

        @Test
        @DisplayName("should return list of files for valid site")
        void shouldReturnListOfFilesForValidSite() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            LatestFileInfoWithS3Key file1 = mockFileInfo("customers.csv.gz", 1024L, Instant.now());
            LatestFileInfoWithS3Key file2 = mockFileInfo("orders.csv.gz", 2048L, Instant.now());
            when(uploadedFileRepository.findLatestByOriginalFileNameForSite(siteId))
                    .thenReturn(List.of(file1, file2));

            // When
            List<FileDto> result = service.listFiles(accountId, siteId);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).fileName()).isEqualTo("customers.csv.gz");
            assertThat(result.get(0).fileSize()).isEqualTo(1024L);
            assertThat(result.get(1).fileName()).isEqualTo("orders.csv.gz");
        }

        @Test
        @DisplayName("should return empty list when no files exist")
        void shouldReturnEmptyListWhenNoFilesExist() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
            when(uploadedFileRepository.findLatestByOriginalFileNameForSite(siteId))
                    .thenReturn(List.of());

            // When
            List<FileDto> result = service.listFiles(accountId, siteId);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should throw SecurityException when site not found")
        void shouldThrowSecurityExceptionWhenSiteNotFound() {
            // Given
            when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.listFiles(accountId, siteId))
                    .isInstanceOf(SecurityException.class)
                    .hasMessage("Site not found");
        }

        @Test
        @DisplayName("should throw SecurityException when site belongs to different account")
        void shouldThrowSecurityExceptionWhenSiteBelongsToDifferentAccount() {
            // Given
            UUID otherAccountId = UUID.randomUUID();
            Site site = mockSite(otherAccountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            // When/Then
            assertThatThrownBy(() -> service.listFiles(accountId, siteId))
                    .isInstanceOf(SecurityException.class)
                    .hasMessage("Site does not belong to your account");
        }
    }

    @Nested
    @DisplayName("downloadFile()")
    class DownloadFileMethod {

        @Test
        @DisplayName("should download file from S3")
        void shouldDownloadFileFromS3() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            String fileName = "customers.csv.gz";
            String s3Key = "some/s3/key/customers.csv.gz";
            LatestFileInfoWithS3Key fileInfo = mockFileInfo(fileName, 1024L, Instant.now(), s3Key);
            when(uploadedFileRepository.findLatestByOriginalFileNameForSiteAndFileName(siteId, fileName))
                    .thenReturn(Optional.of(fileInfo));

            byte[] fileContent = "csv,content".getBytes();
            ResponseInputStream<GetObjectResponse> s3Response = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(fileContent)
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Response);

            // When
            CsvFileQueryService.FileDownloadResult result = service.downloadFile(accountId, siteId, fileName);

            // Then
            assertThat(result.fileName()).isEqualTo(fileName);
            assertThat(result.fileSize()).isEqualTo(1024L);
            assertThat(result.contentType()).isEqualTo("application/gzip");
        }

        @Test
        @DisplayName("should throw FileNotFoundException when file not found")
        void shouldThrowFileNotFoundExceptionWhenFileNotFound() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
            when(uploadedFileRepository.findLatestByOriginalFileNameForSiteAndFileName(siteId, "missing.csv"))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.downloadFile(accountId, siteId, "missing.csv"))
                    .isInstanceOf(CsvFileQueryService.FileNotFoundException.class)
                    .hasMessage("File not found: missing.csv");
        }

        @Test
        @DisplayName("should throw FileDownloadException when S3 fails")
        void shouldThrowFileDownloadExceptionWhenS3Fails() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            String fileName = "customers.csv.gz";
            String s3Key = "some/s3/key/customers.csv.gz";
            LatestFileInfoWithS3Key fileInfo = mockFileInfo(fileName, 1024L, Instant.now(), s3Key);
            when(uploadedFileRepository.findLatestByOriginalFileNameForSiteAndFileName(siteId, fileName))
                    .thenReturn(Optional.of(fileInfo));

            when(s3Client.getObject(any(GetObjectRequest.class)))
                    .thenThrow(S3Exception.builder().message("S3 error").build());

            // When/Then
            assertThatThrownBy(() -> service.downloadFile(accountId, siteId, fileName))
                    .isInstanceOf(CsvFileQueryService.FileDownloadException.class)
                    .hasMessageContaining("Failed to download file");
        }
    }

    @Nested
    @DisplayName("Content Type Determination")
    class ContentTypeDetermination {

        @Test
        @DisplayName("should return application/gzip for .csv.gz files")
        void shouldReturnGzipContentTypeForCsvGz() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            LatestFileInfoWithS3Key fileInfo = mockFileInfo("data.csv.gz", 100L, Instant.now(), "key");
            when(uploadedFileRepository.findLatestByOriginalFileNameForSiteAndFileName(siteId, "data.csv.gz"))
                    .thenReturn(Optional.of(fileInfo));

            ResponseInputStream<GetObjectResponse> s3Response = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(new byte[0])
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Response);

            // When
            CsvFileQueryService.FileDownloadResult result = service.downloadFile(accountId, siteId, "data.csv.gz");

            // Then
            assertThat(result.contentType()).isEqualTo("application/gzip");
        }

        @Test
        @DisplayName("should return text/csv for .csv files")
        void shouldReturnTextCsvContentTypeForCsv() {
            // Given
            Site site = mockSite(accountId, siteId);
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));

            LatestFileInfoWithS3Key fileInfo = mockFileInfo("data.csv", 100L, Instant.now(), "key");
            when(uploadedFileRepository.findLatestByOriginalFileNameForSiteAndFileName(siteId, "data.csv"))
                    .thenReturn(Optional.of(fileInfo));

            ResponseInputStream<GetObjectResponse> s3Response = new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(new byte[0])
            );
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(s3Response);

            // When
            CsvFileQueryService.FileDownloadResult result = service.downloadFile(accountId, siteId, "data.csv");

            // Then
            assertThat(result.contentType()).isEqualTo("text/csv; charset=utf-8");
        }
    }

    // Helper methods

    private Site mockSite(UUID ownerAccountId, UUID id) {
        Site site = mock(Site.class);
        when(site.getId()).thenReturn(id);
        when(site.getAccountId()).thenReturn(ownerAccountId);
        return site;
    }

    private LatestFileInfoWithS3Key mockFileInfo(String fileName, Long fileSize, Instant uploadedAt) {
        return mockFileInfo(fileName, fileSize, uploadedAt, "default/s3/key");
    }

    private LatestFileInfoWithS3Key mockFileInfo(String fileName, Long fileSize, Instant uploadedAt, String s3Key) {
        LatestFileInfoWithS3Key info = mock(LatestFileInfoWithS3Key.class);
        when(info.getOriginalFileName()).thenReturn(fileName);
        when(info.getFileSize()).thenReturn(fileSize);
        when(info.getUploadedAt()).thenReturn(uploadedAt);
        when(info.getS3Key()).thenReturn(s3Key);
        return info;
    }
}
