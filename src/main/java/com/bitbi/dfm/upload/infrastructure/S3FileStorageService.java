package com.bitbi.dfm.upload.infrastructure;

import com.bitbi.dfm.shared.storage.S3PrefixLister;
import com.bitbi.dfm.shared.storage.S3PrefixListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;

/**
 * S3-based storage service for historical uploaded files.
 * <p>
 * Provides S3 lookup and cleanup operations for historical uploaded files.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class S3FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3FileStorageService.class);
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
     * Every object under a prefix, following all S3 continuation pages (issue #122).
     *
     * <p>A mid-pagination failure returns the pages already read plus {@code truncated=true}
     * instead of throwing the whole walk away. {@code BatchDeletionService} and
     * {@code BatchRetentionService} keep their complete-listing behaviour and fall back to
     * recorded exact keys only when the result is truncated.</p>
     *
     * @param prefix the prefix to enumerate
     * @return the pages read, with lastModified per object and a truncation flag
     */
    public S3PrefixListing listAllKeys(String prefix) {
        return S3PrefixLister.listAll(s3Client, bucketName, prefix);
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
