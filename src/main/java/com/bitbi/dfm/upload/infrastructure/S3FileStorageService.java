package com.bitbi.dfm.upload.infrastructure;

import com.bitbi.dfm.upload.domain.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * S3-based file storage service with retry logic.
 * <p>
 * Uploads files to AWS S3 with automatic retry (3 attempts)
 * and multipart file support.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class S3FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3FileStorageService.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 100; // Base delay for exponential backoff
    private static final long MAX_DELAY_MS = 5000; // Maximum delay cap
    private static final int MAX_DELETE_BATCH = 1000;

    private final S3Client s3Client;
    private final String bucketName;

    public S3FileStorageService(
            S3Client s3Client,
            @Value("${s3.bucket.name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Upload file to S3 with retry logic.
     *
     * @param file       multipart file to upload
     * @param s3Path     S3 directory path (e.g., "accountId/domain/date/time/")
     * @param fileName   target file name
     * @return S3 object key (full path)
     * @throws FileStorageException if upload fails after all retries
     */
    public String uploadFile(MultipartFile file, String s3Path, String fileName) {
        String s3Key = s3Path + fileName;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                logger.debug("Uploading file to S3: bucket={}, key={}, attempt={}/{}",
                           bucketName, s3Key, attempt, MAX_RETRIES);

                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType(file.getContentType())
                        .contentLength(file.getSize())
                        .build();

                s3Client.putObject(putObjectRequest,
                                 RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

                logger.info("Successfully uploaded file to S3: key={}", s3Key);
                return s3Key;

            } catch (S3Exception e) {
                logger.warn("S3 upload failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new FileStorageException(
                            "Failed to upload file to S3 after " + MAX_RETRIES + " attempts: " + fileName, e);
                }
                // Exponential backoff with jitter
                long delay = calculateBackoffDelay(attempt);
                logger.debug("Retrying after {} ms", delay);
                sleep(delay);

            } catch (IOException e) {
                throw new FileStorageException("Failed to read file content: " + fileName, e);
            }
        }

        throw new FileStorageException("Failed to upload file to S3: " + fileName);
    }

    /**
     * Calculate SHA-256 checksum for file.
     * <p>
     * Uses SHA-256 instead of MD5 for stronger integrity verification.
     * </p>
     *
     * @param file multipart file
     * @return hex-encoded SHA-256 checksum
     * @throws FileStorageException if checksum calculation fails
     */
    public String calculateChecksum(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                sha256.update(buffer, 0, bytesRead);
            }

            byte[] digest = sha256.digest();
            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException e) {
            throw new FileStorageException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            throw new FileStorageException("Failed to read file for checksum calculation", e);
        }
    }

    /**
     * Check if file exists in S3.
     *
     * @param s3Key full S3 object key
     * @return true if file exists
     */
    public boolean fileExists(String s3Key) {
        try {
            s3Client.headObject(builder -> builder
                    .bucket(bucketName)
                    .key(s3Key));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new FileStorageException("Failed to check file existence: " + s3Key, e);
        }
    }

    /**
     * Delete file from S3.
     * <p>
     * Permanently deletes an object from S3 bucket.
     * If the file doesn't exist, no error is thrown (idempotent operation).
     * </p>
     *
     * @param s3Key full S3 object key
     * @throws FileStorageException if deletion fails
     */
    public void deleteFile(String s3Key) {
        try {
            logger.debug("Deleting file from S3: bucket={}, key={}", bucketName, s3Key);

            s3Client.deleteObject(builder -> builder
                    .bucket(bucketName)
                    .key(s3Key));

            logger.info("Successfully deleted file from S3: key={}", s3Key);

        } catch (S3Exception e) {
            // 404 means file doesn't exist - treat as success (idempotent)
            if (e.statusCode() == 404) {
                logger.warn("File not found in S3 (already deleted?): key={}", s3Key);
                return;
            }
            throw new FileStorageException("Failed to delete file from S3: " + s3Key, e);
        }
    }

    /**
     * Delete multiple files from S3 in batches (up to 1000 per request).
     * <p>
     * Returns a summary with deleted count and errors (if any).
     * </p>
     *
     * @param s3Keys list of S3 object keys
     * @return delete result summary
     */
    public DeleteObjectsResult deleteObjects(List<String> s3Keys) {
        if (s3Keys == null || s3Keys.isEmpty()) {
            return new DeleteObjectsResult(0, List.of());
        }

        int deletedCount = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < s3Keys.size(); i += MAX_DELETE_BATCH) {
            List<String> batch = s3Keys.subList(i, Math.min(i + MAX_DELETE_BATCH, s3Keys.size()));
            try {
                Delete delete = Delete.builder()
                        .objects(batch.stream()
                                .map(key -> ObjectIdentifier.builder().key(key).build())
                                .toList())
                        .build();

                DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(delete)
                        .build();

                DeleteObjectsResponse response = s3Client.deleteObjects(request);
                deletedCount += response.deleted().size();

                for (S3Error error : response.errors()) {
                    errors.add(error.key() + ": " + error.code());
                }
            } catch (S3Exception e) {
                logger.error("Failed to delete S3 objects batch: {}", e.getMessage(), e);
                errors.add("S3Exception: " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            logger.warn("S3 deleteObjects completed with {} errors", errors.size());
        } else {
            logger.info("S3 deleteObjects completed: deleted {} objects", deletedCount);
        }

        return new DeleteObjectsResult(deletedCount, errors);
    }

    /**
     * Calculate exponential backoff delay with jitter.
     * <p>
     * Formula: min(BASE_DELAY * 2^(attempt-1) + random(0, BASE_DELAY), MAX_DELAY)
     * </p>
     * <p>
     * Example delays:
     * - Attempt 1: 100-200ms
     * - Attempt 2: 200-300ms
     * - Attempt 3: 400-500ms
     * </p>
     *
     * @param attempt current retry attempt (1-based)
     * @return delay in milliseconds
     */
    private long calculateBackoffDelay(int attempt) {
        // Exponential backoff: base * 2^(attempt-1)
        long exponentialDelay = BASE_DELAY_MS * (1L << (attempt - 1));

        // Add jitter: random value between 0 and BASE_DELAY_MS
        long jitter = (long) (Math.random() * BASE_DELAY_MS);

        // Cap at MAX_DELAY_MS
        return Math.min(exponentialDelay + jitter, MAX_DELAY_MS);
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileStorageException("Upload retry interrupted", e);
        }
    }

    /**
     * Exception thrown when file storage operations fail.
     */
    public static class FileStorageException extends RuntimeException {
        public FileStorageException(String message) {
            super(message);
        }

        public FileStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Result summary for bulk delete operations.
     *
     * @param deletedCount number of successfully deleted objects
     * @param errors list of error descriptions (key + error code/message)
     */
    public record DeleteObjectsResult(int deletedCount, List<String> errors) {}
}
