package com.bitbi.dfm.upload.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FileUploadService — focuses on validateUploadPreconditions and
 * file type validation logic added in feature/019.
 */
@DisplayName("FileUploadService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileUploadServiceTest {

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private S3FileStorageService s3FileStorageService;

    @Mock
    private SiteRepository siteRepository;

    private FileUploadService service;

    @BeforeEach
    void setUp() {
        service = new FileUploadService(uploadedFileRepository, batchRepository,
                s3FileStorageService, siteRepository);
    }

    // --- helpers ---

    private Batch mockInProgressBatch(UUID batchId, UUID siteId, UUID accountId) {
        Batch batch = mock(Batch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getSiteId()).thenReturn(siteId);
        when(batch.getAccountId()).thenReturn(accountId);
        when(batch.getStatus()).thenReturn(BatchStatus.IN_PROGRESS);
        when(batch.getS3Path()).thenReturn("account/site/batch/");
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        return batch;
    }

    private Site mockSite(UUID siteId, SiteType siteType) {
        Site site = mock(Site.class);
        when(site.getSiteType()).thenReturn(siteType);
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
        return site;
    }

    private MultipartFile mockFile(String filename, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getSize()).thenReturn(size);
        when(file.getContentType()).thenReturn("application/octet-stream");
        return file;
    }

    @Nested
    @DisplayName("Batch validation")
    class BatchValidation {

        @Test
        @DisplayName("should throw BatchNotFoundException when batch not found")
        void shouldThrowWhenBatchNotFound() {
            UUID batchId = UUID.randomUUID();
            when(batchRepository.findById(batchId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("data.csv", 100)))
                    .isInstanceOf(FileUploadService.BatchNotFoundException.class)
                    .hasMessageContaining("Batch not found");
        }

        @Test
        @DisplayName("should throw InvalidBatchStatusException when batch is COMPLETED")
        void shouldThrowWhenBatchIsCompleted() {
            UUID batchId = UUID.randomUUID();
            Batch batch = mock(Batch.class);
            when(batch.getStatus()).thenReturn(BatchStatus.COMPLETED);
            when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("data.csv", 100)))
                    .isInstanceOf(FileUploadService.InvalidBatchStatusException.class)
                    .hasMessageContaining("Cannot upload to batch");
        }

        @Test
        @DisplayName("should throw FileSizeExceededException when file is too large")
        void shouldThrowWhenFileTooLarge() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);

            long oversized = 128L * 1024 * 1024 + 1; // 128 MB + 1 byte
            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("large.csv", oversized)))
                    .isInstanceOf(FileUploadService.FileSizeExceededException.class);
        }

        @Test
        @DisplayName("should throw InvalidFileException when filename is blank")
        void shouldThrowWhenFilenameBlank() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);

            MultipartFile file = mock(MultipartFile.class);
            when(file.getOriginalFilename()).thenReturn("   ");
            when(file.getSize()).thenReturn(100L);

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, file))
                    .isInstanceOf(FileUploadService.InvalidFileException.class)
                    .hasMessageContaining("File name cannot be empty");
        }

        @Test
        @DisplayName("should throw DuplicateFileException when file already exists in batch")
        void shouldThrowForDuplicateFile() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);

            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(batchId, "data.csv"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("data.csv", 100)))
                    .isInstanceOf(FileUploadService.DuplicateFileException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("File type validation — DBF sites")
    class DbfFileTypeValidation {

        @Test
        @DisplayName("should allow CSV file for DBF site")
        void shouldAllowCsvForDbfSite() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);
            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(any(), any())).thenReturn(false);

            // Should not throw
            service.validateUploadPreconditions(batchId, mockFile("report.csv", 1024));
        }

        @Test
        @DisplayName("should allow CSV.GZ file for DBF site")
        void shouldAllowCsvGzForDbfSite() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);
            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(any(), any())).thenReturn(false);

            service.validateUploadPreconditions(batchId, mockFile("report.csv.gz", 512));
        }

        @Test
        @DisplayName("should reject JSONL file for DBF site")
        void shouldRejectJsonlForDbfSite() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("changes.jsonl", 1024)))
                    .isInstanceOf(FileUploadService.InvalidFileTypeException.class)
                    .hasMessageContaining("JSONL files not supported for DBF sites");
        }

        @Test
        @DisplayName("should reject JSONL.GZ file for DBF site")
        void shouldRejectJsonlGzForDbfSite() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.DBF);

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("changes.jsonl.gz", 512)))
                    .isInstanceOf(FileUploadService.InvalidFileTypeException.class)
                    .hasMessageContaining("JSONL files not supported for DBF sites");
        }
    }

    @Nested
    @DisplayName("File type validation — POSTGRES_CDC sites (baseline batch)")
    class CdcBaselineBatchValidation {

        @Test
        @DisplayName("should allow CSV file for CDC baseline batch")
        void shouldAllowCsvForCdcBaseline() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.POSTGRES_CDC);
            when(batchRepository.findPreviousBatchForSite(siteId, batchId)).thenReturn(Optional.empty());
            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(any(), any())).thenReturn(false);

            service.validateUploadPreconditions(batchId, mockFile("snapshot.csv", 2048));
        }

        @Test
        @DisplayName("should reject JSONL file for CDC baseline batch")
        void shouldRejectJsonlForCdcBaseline() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.POSTGRES_CDC);
            when(batchRepository.findPreviousBatchForSite(siteId, batchId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("delta.jsonl", 1024)))
                    .isInstanceOf(FileUploadService.InvalidFileTypeException.class)
                    .hasMessageContaining("first (baseline) batch");
        }
    }

    @Nested
    @DisplayName("File type validation — POSTGRES_CDC sites (delta batch)")
    class CdcDeltaBatchValidation {

        @Test
        @DisplayName("should allow JSONL file for CDC delta batch")
        void shouldAllowJsonlForCdcDelta() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            Batch prevBatch = mock(Batch.class);
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.POSTGRES_CDC);
            when(batchRepository.findPreviousBatchForSite(siteId, batchId)).thenReturn(Optional.of(prevBatch));
            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(any(), any())).thenReturn(false);

            service.validateUploadPreconditions(batchId, mockFile("changes.jsonl", 512));
        }

        @Test
        @DisplayName("should allow JSONL.GZ file for CDC delta batch")
        void shouldAllowJsonlGzForCdcDelta() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            Batch prevBatch = mock(Batch.class);
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.POSTGRES_CDC);
            when(batchRepository.findPreviousBatchForSite(siteId, batchId)).thenReturn(Optional.of(prevBatch));
            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(any(), any())).thenReturn(false);

            service.validateUploadPreconditions(batchId, mockFile("changes.jsonl.gz", 256));
        }

        @Test
        @DisplayName("should reject CSV file for CDC delta batch")
        void shouldRejectCsvForCdcDelta() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            Batch prevBatch = mock(Batch.class);
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.POSTGRES_CDC);
            when(batchRepository.findPreviousBatchForSite(siteId, batchId)).thenReturn(Optional.of(prevBatch));

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("full_dump.csv", 1024)))
                    .isInstanceOf(FileUploadService.InvalidFileTypeException.class)
                    .hasMessageContaining("CDC delta batches");
        }

        @Test
        @DisplayName("should reject unsupported extension for CDC delta batch")
        void shouldRejectUnsupportedExtensionForCdcDelta() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            Batch prevBatch = mock(Batch.class);
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            mockSite(siteId, SiteType.POSTGRES_CDC);
            when(batchRepository.findPreviousBatchForSite(siteId, batchId)).thenReturn(Optional.of(prevBatch));

            assertThatThrownBy(() -> service.validateUploadPreconditions(batchId, mockFile("data.xml", 1024)))
                    .isInstanceOf(FileUploadService.InvalidFileTypeException.class)
                    .hasMessageContaining("Unsupported file type");
        }
    }

    @Nested
    @DisplayName("Site not found — fallback")
    class SiteNotFound {

        @Test
        @DisplayName("should allow upload when site not found (validation skipped)")
        void shouldAllowWhenSiteNotFound() {
            UUID batchId = UUID.randomUUID();
            UUID siteId = UUID.randomUUID();
            mockInProgressBatch(batchId, siteId, UUID.randomUUID());
            when(siteRepository.findById(siteId)).thenReturn(Optional.empty());
            when(uploadedFileRepository.existsByBatchIdAndOriginalFileName(any(), any())).thenReturn(false);

            // Should not throw — site not found is a pass-through
            service.validateUploadPreconditions(batchId, mockFile("data.csv", 100));
        }
    }
}
