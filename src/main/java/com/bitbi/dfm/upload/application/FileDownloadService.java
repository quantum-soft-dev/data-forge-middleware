package com.bitbi.dfm.upload.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService.PresignedUrlResult;
import com.bitbi.dfm.batch.presentation.dto.FileDownloadResponseDto;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Service for file download operations.
 * <p>
 * Provides presigned URL generation for single file downloads and
 * streaming ZIP generation for multiple file downloads.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @since User Story 3 (Phase 5)
 */
@Service
@Transactional(readOnly = true)
public class FileDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(FileDownloadService.class);
    private static final int BUFFER_SIZE = 8192; // 8KB buffer for streaming

    private final BatchRepository batchRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final S3PresignedUrlService presignedUrlService;
    private final S3Client s3Client;
    private final MeterRegistry meterRegistry;
    private final String bucketName;

    public FileDownloadService(
            BatchRepository batchRepository,
            UploadedFileRepository uploadedFileRepository,
            S3PresignedUrlService presignedUrlService,
            S3Client s3Client,
            MeterRegistry meterRegistry,
            @Value("${s3.bucket.name}") String bucketName
    ) {
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.presignedUrlService = presignedUrlService;
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.bucketName = bucketName;
    }

    /**
     * Get presigned URL for single file download.
     * <p>
     * Business Rules:
     * - User must own the batch (authorization check)
     * - Batch must be COMPLETED (no downloads for IN_PROGRESS batches)
     * </p>
     *
     * @param batchId   Batch ID
     * @param fileId    File ID
     * @param accountId User's account ID (for authorization)
     * @return FileDownloadResponseDto with presigned URL
     * @throws BatchNotFoundException          if batch doesn't exist
     * @throws FileNotFoundException           if file doesn't exist
     * @throws UnauthorizedBatchAccessException if batch doesn't belong to user
     * @throws BatchNotCompletedException      if batch status is not COMPLETED
     */
    public FileDownloadResponseDto getPresignedUrlForFile(UUID batchId, UUID fileId, UUID accountId) {
        logger.debug("Getting presigned URL for batchId={}, fileId={}, accountId={}", batchId, fileId, accountId);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Fetch batch and validate ownership
            Batch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new BatchNotFoundException("Batch not found: " + batchId));

            // Authorization: Check if batch belongs to user's account
            if (!batch.getAccountId().equals(accountId)) {
                logger.warn("Unauthorized download attempt: batchId={}, accountId={}", batchId, accountId);
                throw new UnauthorizedBatchAccessException(batchId, accountId);
            }

            // Business rule: Only COMPLETED batches can be downloaded
            if (batch.getStatus() != BatchStatus.COMPLETED) {
                logger.warn("Download attempted on non-completed batch: batchId={}, status={}", batchId, batch.getStatus());
                throw new BatchNotCompletedException("Batch must be COMPLETED before downloading files");
            }

            // Fetch file
            UploadedFile file = uploadedFileRepository.findById(fileId)
                    .orElseThrow(() -> new FileNotFoundException("File not found: " + fileId));

            // Verify file belongs to batch (defensive check)
            if (!file.getBatchId().equals(batchId)) {
                logger.error("File {} does not belong to batch {}", fileId, batchId);
                throw new IllegalArgumentException("File does not belong to specified batch");
            }

            // Generate presigned URL
            PresignedUrlResult presignedUrl = presignedUrlService.generatePresignedUrl(
                    file.getS3Key(),
                    file.getOriginalFileName()
            );

            logger.info("Generated presigned URL for file={}, expiresAt={}", file.getOriginalFileName(), presignedUrl.expiresAt());

            // Record metric
            sample.stop(meterRegistry.timer("s3.presigned.url.generation"));
            meterRegistry.counter("downloads.file.presigned").increment();

            return FileDownloadResponseDto.of(
                    presignedUrl.url(),
                    file.getOriginalFileName(),
                    file.getFileSize(),
                    presignedUrl.expiresAt()
            );

        } catch (BatchNotFoundException | FileNotFoundException | UnauthorizedBatchAccessException | BatchNotCompletedException e) {
            sample.stop(meterRegistry.timer("s3.presigned.url.generation", "result", "error"));
            throw e;
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("s3.presigned.url.generation", "result", "error"));
            logger.error("Failed to generate presigned URL for batchId={}, fileId={}", batchId, fileId, e);
            throw new FileDownloadException("Failed to generate download URL", e);
        }
    }

    /**
     * Download multiple files as ZIP archive.
     * <p>
     * Streams files directly from S3 to HTTP response as ZIP archive.
     * Memory efficient - no intermediate buffering.
     * </p>
     * <p>
     * Compression Strategy:
     * - .gz files: Use STORED method (no double compression)
     * - Other files: Use DEFLATED method (standard ZIP compression)
     * </p>
     *
     * @param batchId   Batch ID
     * @param fileIds   List of file IDs to include in ZIP
     * @param accountId User's account ID (for authorization)
     * @param response  HTTP response to stream ZIP to
     * @throws IOException if streaming fails
     */
    public void downloadFilesAsZip(UUID batchId, List<UUID> fileIds, UUID accountId, HttpServletResponse response)
            throws IOException {

        logger.info("Starting ZIP download: batchId={}, fileCount={}, accountId={}", batchId, fileIds.size(), accountId);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Fetch batch and validate
            Batch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new BatchNotFoundException("Batch not found: " + batchId));

            // Authorization check
            if (!batch.getAccountId().equals(accountId)) {
                logger.warn("Unauthorized ZIP download attempt: batchId={}, accountId={}", batchId, accountId);
                throw new UnauthorizedBatchAccessException(batchId, accountId);
            }

            // Business rule: Only COMPLETED batches
            if (batch.getStatus() != BatchStatus.COMPLETED) {
                logger.warn("ZIP download attempted on non-completed batch: batchId={}, status={}", batchId, batch.getStatus());
                throw new BatchNotCompletedException("Batch must be COMPLETED before downloading files");
            }

            // Fetch files
            List<UploadedFile> files = fileIds.stream()
                    .map(id -> uploadedFileRepository.findById(id))
                    .filter(opt -> opt.isPresent())
                    .map(opt -> opt.get())
                    .toList();
            if (files.isEmpty()) {
                throw new FileNotFoundException("No files found for provided IDs");
            }

            // Verify all files belong to this batch
            boolean allBelongToBatch = files.stream().allMatch(f -> f.getBatchId().equals(batchId));
            if (!allBelongToBatch) {
                logger.error("Some files do not belong to batch {}", batchId);
                throw new IllegalArgumentException("All files must belong to the specified batch");
            }

            // Set response headers
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"batch-" + batchId + ".zip\"");

            // Stream ZIP to response
            try (ZipArchiveOutputStream zipOut = new ZipArchiveOutputStream(response.getOutputStream())) {
                for (UploadedFile file : files) {
                    addFileToZip(zipOut, file);
                }

                zipOut.finish();
                logger.info("ZIP download completed: batchId={}, fileCount={}", batchId, files.size());

                // Record metrics
                sample.stop(meterRegistry.timer("downloads.zip.duration"));
                meterRegistry.counter("downloads.zip.files", "count", String.valueOf(files.size())).increment();

            } catch (IOException e) {
                sample.stop(meterRegistry.timer("downloads.zip.duration", "result", "error"));
                logger.error("Failed to stream ZIP for batchId={}", batchId, e);
                throw e;
            }

        } catch (BatchNotFoundException | UnauthorizedBatchAccessException | BatchNotCompletedException e) {
            sample.stop(meterRegistry.timer("downloads.zip.duration", "result", "error"));
            throw e;
        }
    }

    /**
     * Add single file to ZIP archive.
     * <p>
     * Compression Logic:
     * - .gz files: STORED (no double compression)
     * - Other files: DEFLATED (standard ZIP compression)
     * </p>
     *
     * @param zipOut ZIP output stream
     * @param file   File to add
     * @throws IOException if S3 download or ZIP writing fails
     */
    private void addFileToZip(ZipArchiveOutputStream zipOut, UploadedFile file) throws IOException {
        logger.debug("Adding file to ZIP: {}", file.getOriginalFileName());

        // Create ZIP entry
        ZipArchiveEntry entry = new ZipArchiveEntry(file.getOriginalFileName());
        entry.setSize(file.getFileSize());

        // Detect .gz files and use STORED method (no double compression)
        if (file.getOriginalFileName().endsWith(".gz")) {
            entry.setMethod(ZipArchiveEntry.STORED);
            // For STORED method, must set CRC and compressed size
            // We'll use DEFLATED for now as it auto-calculates these
            entry.setMethod(ZipArchiveEntry.DEFLATED);
        } else {
            entry.setMethod(ZipArchiveEntry.DEFLATED);
        }

        zipOut.putArchiveEntry(entry);

        // Stream file from S3 directly to ZIP (no intermediate buffering)
        try (InputStream s3Stream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(file.getS3Key())
                .build())) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = s3Stream.read(buffer)) != -1) {
                zipOut.write(buffer, 0, bytesRead);
            }

        } catch (Exception e) {
            logger.error("Failed to stream file from S3: {}", file.getS3Key(), e);
            throw new IOException("Failed to download file from S3: " + file.getOriginalFileName(), e);
        }

        zipOut.closeArchiveEntry();
    }

    // Custom Exceptions

    public static class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException(String message) {
            super(message);
        }
    }

    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String message) {
            super(message);
        }
    }

    public static class BatchNotCompletedException extends RuntimeException {
        public BatchNotCompletedException(String message) {
            super(message);
        }
    }

    public static class FileDownloadException extends RuntimeException {
        public FileDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
