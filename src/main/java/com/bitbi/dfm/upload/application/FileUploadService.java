package com.bitbi.dfm.upload.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.upload.domain.FileChecksum;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Application service for file upload operations.
 * <p>
 * Handles multipart file uploads with:
 * - S3 storage integration
 * - MD5 checksum calculation
 * - Duplicate filename detection
 * - Batch validation
 * </p>
 * <p>
 * Transaction management: Methods use explicit @Transactional annotations
 * to control transaction boundaries. S3 uploads occur outside transactions
 * to prevent orphaned files.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class FileUploadService {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadService.class);
    private static final long MAX_FILE_SIZE_BYTES = 128L * 1024 * 1024; // 128 MB (aligned with spring.servlet.multipart.max-file-size)

    private final UploadedFileRepository uploadedFileRepository;
    private final BatchRepository batchRepository;
    private final S3FileStorageService s3FileStorageService;

    public FileUploadService(
            UploadedFileRepository uploadedFileRepository,
            BatchRepository batchRepository,
            S3FileStorageService s3FileStorageService) {
        this.uploadedFileRepository = uploadedFileRepository;
        this.batchRepository = batchRepository;
        this.s3FileStorageService = s3FileStorageService;
    }

    /**
     * Upload file to batch.
     * <p>
     * Business rules:
     * - Batch must be IN_PROGRESS
     * - No duplicate filenames within batch
     * - File size must not exceed 128 MB
     * </p>
     * <p>
     * Transaction boundary: S3 upload happens OUTSIDE transaction to prevent orphaned files.
     * If transaction fails after upload, file remains in S3 but no metadata is saved.
     * </p>
     *
     * @param batchId batch identifier
     * @param file    multipart file to upload
     * @return uploaded file metadata
     * @throws BatchNotFoundException          if batch not found
     * @throws InvalidBatchStatusException  if batch is not IN_PROGRESS
     * @throws DuplicateFileException       if filename already exists in batch
     * @throws FileSizeExceededException    if file size exceeds limit
     */
    public UploadedFile uploadFile(UUID batchId, MultipartFile file) {
        logger.info("Uploading file to batch: batchId={}, filename={}, size={} bytes",
                   batchId, file.getOriginalFilename(), file.getSize());

        // Phase 1: Validate (within transaction context if called from @Transactional)
        Batch batch = validateUploadPreconditions(batchId, file);

        String fileName = file.getOriginalFilename();
        String s3Path = batch.getS3Path();

        // Calculate checksum before upload
        String checksumValue = s3FileStorageService.calculateChecksum(file);
        FileChecksum checksum = FileChecksum.fromHex(checksumValue);

        // Phase 2: Upload to S3 (OUTSIDE transaction - happens first)
        // If this fails, no database changes occur
        String s3Key = s3FileStorageService.uploadFile(file, s3Path, fileName);

        // Phase 3: Save metadata in new transaction
        // If this fails, file remains in S3 but will be orphaned (acceptable tradeoff)
        return saveUploadMetadata(batchId, batch, file, fileName, s3Path, s3Key, checksum);
    }

    /**
     * Validate upload preconditions (batch status, file size, duplicate check).
     * <p>
     * Called within transaction context to ensure consistent state.
     * </p>
     */
    @Transactional(readOnly = true)
    protected Batch validateUploadPreconditions(UUID batchId, MultipartFile file) {
        // Validate batch
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BatchNotFoundException("Batch not found: " + batchId));

        if (batch.getStatus() != BatchStatus.IN_PROGRESS) {
            throw new InvalidBatchStatusException(
                    "Cannot upload to batch in status: " + batch.getStatus());
        }

        // Validate file size
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new FileSizeExceededException(
                    String.format("File size %d exceeds maximum %d bytes", file.getSize(), MAX_FILE_SIZE_BYTES));
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidFileException("File name cannot be empty");
        }

        // Check for duplicate filename
        if (uploadedFileRepository.existsByBatchIdAndOriginalFileName(batchId, fileName)) {
            throw new DuplicateFileException("File '" + fileName + "' already exists in this batch");
        }

        return batch;
    }

    /**
     * Save upload metadata in database.
     * <p>
     * Runs in new transaction to ensure atomic commit.
     * If this fails, S3 file is orphaned (acceptable tradeoff vs rolling back S3 upload).
     * </p>
     */
    @Transactional
    protected UploadedFile saveUploadMetadata(UUID batchId, Batch batch, MultipartFile file,
                                               String fileName, String s3Path, String s3Key,
                                               FileChecksum checksum) {
        // Re-fetch batch in this transaction to get latest state
        Batch currentBatch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BatchNotFoundException("Batch not found: " + batchId));

        // Double-check status hasn't changed
        if (currentBatch.getStatus() != BatchStatus.IN_PROGRESS) {
            logger.warn("Batch status changed during upload: batchId={}, newStatus={}, s3Key={}",
                       batchId, currentBatch.getStatus(), s3Key);
            throw new InvalidBatchStatusException(
                    "Cannot save metadata: batch status changed to " + currentBatch.getStatus());
        }

        // Save metadata
        UploadedFile uploadedFile = UploadedFile.create(
                batchId,
                fileName,
                s3Path,
                file.getSize(),
                file.getContentType(),
                checksum
        );

        UploadedFile saved = uploadedFileRepository.save(uploadedFile);

        // Update batch counters
        currentBatch.incrementFileCount(file.getSize());
        batchRepository.save(currentBatch);

        logger.info("File uploaded successfully: batchId={}, fileId={}, s3Key={}, newFileCount={}, newTotalSize={}",
                   batchId, saved.getId(), s3Key, currentBatch.getUploadedFilesCount(), currentBatch.getTotalSize());

        return saved;
    }

    /**
     * Get uploaded file by ID.
     *
     * @param fileId file identifier
     * @return uploaded file
     * @throws FileNotFoundException if file not found
     */
    @Transactional(readOnly = true)
    public UploadedFile getFile(UUID fileId) {
        return uploadedFileRepository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException("File not found: " + fileId));
    }

    /**
     * List all files for batch.
     *
     * @param batchId batch identifier
     * @return list of uploaded files
     */
    @Transactional(readOnly = true)
    public List<UploadedFile> listFilesByBatch(UUID batchId) {
        return uploadedFileRepository.findByBatchId(batchId);
    }

    /**
     * Get total upload count for batch.
     *
     * @param batchId batch identifier
     * @return number of uploaded files
     */
    @Transactional(readOnly = true)
    public long countFilesByBatch(UUID batchId) {
        return uploadedFileRepository.countByBatchId(batchId);
    }

    /**
     * Exception thrown when batch is not found.
     */
    public static class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when batch status is invalid for upload.
     */
    public static class InvalidBatchStatusException extends RuntimeException {
        public InvalidBatchStatusException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when duplicate filename exists.
     */
    public static class DuplicateFileException extends RuntimeException {
        public DuplicateFileException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when file size exceeds limit.
     */
    public static class FileSizeExceededException extends RuntimeException {
        public FileSizeExceededException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when file is invalid.
     */
    public static class InvalidFileException extends RuntimeException {
        public InvalidFileException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when file is not found.
     */
    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String message) {
            super(message);
        }
    }
}
