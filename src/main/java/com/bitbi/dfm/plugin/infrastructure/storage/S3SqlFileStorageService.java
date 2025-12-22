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
     * @param siteName The site name (used in path)
     * @param sqlContent The SQL content to store
     * @return The S3 key where the file was stored
     */
    public String storeSqlFile(UUID accountId, String siteName, String sqlContent) {
        String s3Key = generateS3Key(accountId, siteName);
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
     * Generates S3 key for SQL file.
     * Format: plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql
     */
    private String generateS3Key(UUID accountId, String siteName) {
        String datetime = LocalDateTime.now().format(PATH_DATETIME_FORMATTER);
        return String.format("plugins/bit-bi/%s/%s/%s.sql",
                accountId, sanitizeSiteName(siteName), datetime);
    }

    /**
     * Sanitizes site name for use in S3 key.
     * Replaces problematic characters with hyphens.
     */
    private String sanitizeSiteName(String siteName) {
        if (siteName == null || siteName.isEmpty()) {
            return "unknown-site";
        }
        return siteName.replaceAll("[^a-zA-Z0-9.-]", "-").toLowerCase();
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
