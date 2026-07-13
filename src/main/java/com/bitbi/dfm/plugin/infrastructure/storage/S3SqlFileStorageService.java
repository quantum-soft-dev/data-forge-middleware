package com.bitbi.dfm.plugin.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S3 storage service for plugin-generated SQL files.
 * Stores SQL files at path: plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql
 */
@Service
public class S3SqlFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3SqlFileStorageService.class);
    private static final DateTimeFormatter PATH_DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final S3Client s3Client;
    private final String bucketName;

    public S3SqlFileStorageService(
            S3Client s3Client,
            @Value("${s3.bucket.name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Stores generated SQL content in S3.
     *
     * @param accountId The account ID
     * @param siteId    The site ID (used in path, Auth V2)
     * @param sqlContent The SQL content to store
     * @return The S3 key where the file was stored
     */
    public String storeSqlFile(UUID accountId, UUID siteId, String sqlContent) {
        String s3Key = generateS3Key(accountId, siteId);
        byte[] contentBytes = sqlContent.getBytes(StandardCharsets.UTF_8);

        log.debug("Storing SQL file to S3: bucket={}, key={}, size={}",
                bucketName, s3Key, contentBytes.length);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("text/plain; charset=utf-8")
                    .contentLength((long) contentBytes.length)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(contentBytes));

            log.info("Successfully stored SQL file: key={}, size={}", s3Key, contentBytes.length);
            return s3Key;

        } catch (S3Exception e) {
            throw new SqlFileStorageException("Failed to store SQL file in S3: " + s3Key, e);
        }
    }

    /**
     * Retrieves SQL content from S3.
     *
     * @param s3Key The S3 key of the SQL file
     * @return The SQL content as a string
     */
    public String getSqlFileContent(String s3Key) {
        log.debug("Retrieving SQL file from S3: bucket={}, key={}", bucketName, s3Key);

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = response.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                return baos.toString(StandardCharsets.UTF_8);
            }

        } catch (NoSuchKeyException e) {
            throw new SqlFileNotFoundException("SQL file not found: " + s3Key, e);
        } catch (S3Exception e) {
            throw new SqlFileStorageException("Failed to retrieve SQL file from S3: " + s3Key, e);
        } catch (IOException e) {
            throw new SqlFileStorageException("Failed to read SQL file content: " + s3Key, e);
        }
    }

    /**
     * Gets the file size in bytes for an S3 object.
     *
     * @param s3Key The S3 key
     * @return File size in bytes
     */
    public long getFileSize(String s3Key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            HeadObjectResponse response = s3Client.headObject(request);
            return response.contentLength();

        } catch (NoSuchKeyException e) {
            throw new SqlFileNotFoundException("SQL file not found: " + s3Key, e);
        } catch (S3Exception e) {
            throw new SqlFileStorageException("Failed to get SQL file metadata: " + s3Key, e);
        }
    }

    /**
     * Deletes a SQL file from S3.
     * Used to clean up orphaned files when database save fails.
     *
     * @param s3Key The S3 key to delete
     */
    public void deleteFile(String s3Key) {
        log.debug("Deleting SQL file from S3: bucket={}, key={}", bucketName, s3Key);

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(request);
            log.info("Successfully deleted SQL file: key={}", s3Key);

        } catch (S3Exception e) {
            throw new SqlFileStorageException("Failed to delete SQL file from S3: " + s3Key, e);
        }
    }

    /**
     * Deletes multiple SQL files from S3 in batch.
     * Uses AWS S3 batch delete for efficiency (up to 1000 objects per request).
     *
     * @param s3Keys List of S3 keys to delete
     * @return List of keys that failed to delete
     */
    public List<String> deleteFiles(List<String> s3Keys) {
        if (s3Keys == null || s3Keys.isEmpty()) {
            return List.of();
        }

        List<String> failedKeys = new ArrayList<>();
        int batchSize = 1000; // S3 DeleteObjects limit

        for (int i = 0; i < s3Keys.size(); i += batchSize) {
            List<String> batch = s3Keys.subList(i, Math.min(i + batchSize, s3Keys.size()));
            failedKeys.addAll(deleteBatch(batch));
        }

        log.info("Batch delete completed: total={}, failed={}", s3Keys.size(), failedKeys.size());
        return failedKeys;
    }

    /**
     * Deletes a batch of S3 objects (max 1000).
     */
    private List<String> deleteBatch(List<String> keys) {
        List<String> failedKeys = new ArrayList<>();

        try {
            List<ObjectIdentifier> objectIds = keys.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();

            Delete delete = Delete.builder()
                    .objects(objectIds)
                    .quiet(false) // Return info about deleted objects
                    .build();

            DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(delete)
                    .build();

            DeleteObjectsResponse response = s3Client.deleteObjects(request);

            // Collect any errors
            if (response.hasErrors() && !response.errors().isEmpty()) {
                for (S3Error error : response.errors()) {
                    log.warn("Failed to delete S3 object: key={}, code={}, message={}",
                            error.key(), error.code(), error.message());
                    failedKeys.add(error.key());
                }
            }

            int successCount = keys.size() - failedKeys.size();
            log.debug("Batch delete: requested={}, succeeded={}, failed={}",
                    keys.size(), successCount, failedKeys.size());

        } catch (S3Exception e) {
            log.error("Batch delete failed: {}", e.getMessage(), e);
            // If the entire batch fails, all keys are considered failed
            failedKeys.addAll(keys);
        }

        return failedKeys;
    }

    /**
     * Generates S3 key for SQL file (Auth V2).
     * Format: plugins/bit-bi/{accountId}/{siteId}/{datetime}_{suffix}.sql
     *
     * <p>The random suffix keeps keys unique when several generations land within the same
     * second — routine for Delta v2 sites whose time-sealed sessions complete seconds apart
     * (026); without it the later file silently overwrites the earlier one.</p>
     */
    private String generateS3Key(UUID accountId, UUID siteId) {
        String datetime = LocalDateTime.now().format(PATH_DATETIME_FORMATTER);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("plugins/bit-bi/%s/%s/%s_%s.sql",
                accountId, siteId, datetime, suffix);
    }

    /**
     * Exception thrown when SQL file storage operations fail.
     */
    public static class SqlFileStorageException extends RuntimeException {
        public SqlFileStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when SQL file is not found.
     */
    public static class SqlFileNotFoundException extends RuntimeException {
        public SqlFileNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
