package com.bitbi.dfm.batch.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;

/**
 * Service for generating S3 presigned URLs for file downloads.
 * <p>
 * Presigned URLs allow direct client-to-S3 downloads without proxying through the application server.
 * URLs expire after 15 minutes for security.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @since User Story 3 (Phase 5)
 */
@Service
public class S3PresignedUrlService {

    private static final Logger logger = LoggerFactory.getLogger(S3PresignedUrlService.class);
    private static final Duration PRESIGNED_URL_EXPIRY = Duration.ofMinutes(15);

    private final S3Presigner s3Presigner;
    private final String bucketName;

    public S3PresignedUrlService(
            S3Presigner s3Presigner,
            @Value("${s3.bucket.name}") String bucketName
    ) {
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
    }

    /**
     * Sanitize filename to prevent HTTP header injection attacks.
     * <p>
     * Removes or replaces characters that could be used for header injection:
     * - Newlines (\r, \n) - prevent response splitting
     * - Quotes (") - prevent header value escaping
     * - Semicolons (;) - prevent directive injection
     * - Non-ASCII characters - replaced with underscore
     * </p>
     *
     * @param fileName Original filename
     * @return Sanitized filename safe for HTTP headers
     */
    private String sanitizeFilename(String fileName) {
        if (fileName == null) {
            return "download";
        }

        // Remove dangerous characters that could enable header injection
        String sanitized = fileName
                .replace("\r", "")
                .replace("\n", "")
                .replace("\"", "'")
                .replace(";", "_")
                .replace("\\", "_");

        // Replace non-ASCII characters to prevent encoding issues
        sanitized = sanitized.replaceAll("[^\\x20-\\x7E]", "_");

        // Ensure filename is not empty after sanitization
        if (sanitized.isBlank()) {
            return "download";
        }

        // Limit length to prevent excessively long headers (max 255 chars)
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
        }

        return sanitized;
    }

    /**
     * Generate presigned URL for S3 file download.
     * <p>
     * The generated URL allows direct download from S3 without authentication.
     * URL expires after 15 minutes.
     * </p>
     * <p>
     * Security: Filename is sanitized to prevent HTTP header injection attacks.
     * Dangerous characters (newlines, quotes, semicolons) are removed or replaced.
     * </p>
     *
     * @param s3Key    S3 object key
     * @param fileName Suggested filename for Content-Disposition header
     * @return Presigned URL and expiration time
     * @throws IllegalArgumentException if s3Key or fileName is null/empty
     */
    public PresignedUrlResult generatePresignedUrl(String s3Key, String fileName) {
        return generatePresignedUrl(s3Key, fileName, PRESIGNED_URL_EXPIRY);
    }

    /**
     * Generate presigned URL with a caller-chosen expiry (e.g. the ~60 s window used by
     * one-time download links, 028-parquet-export-plugin).
     *
     * @param s3Key    S3 object key
     * @param fileName Suggested filename for Content-Disposition header
     * @param expiry   signature validity duration
     * @return Presigned URL and expiration time
     */
    public PresignedUrlResult generatePresignedUrl(String s3Key, String fileName, Duration expiry) {
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("S3 key cannot be null or empty");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }

        // Sanitize filename to prevent header injection attacks
        String safeFileName = sanitizeFilename(fileName);
        logger.debug("Generating presigned URL for s3Key={}, fileName={} (sanitized: {})",
                s3Key, fileName, safeFileName);

        try {
            // Create GetObjectRequest with Content-Disposition hint
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .responseContentDisposition("attachment; filename=\"" + safeFileName + "\"")
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .getObjectRequest(getObjectRequest)
                    .build();

            // Generate presigned URL
            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            URL presignedUrl = presignedRequest.url();
            Instant expiresAt = Instant.now().plus(expiry);

            logger.info("Generated presigned URL for file={} (sanitized: {}), expiresAt={}",
                    fileName, safeFileName, expiresAt);

            return new PresignedUrlResult(presignedUrl.toString(), expiresAt);

        } catch (Exception e) {
            logger.error("Failed to generate presigned URL for s3Key={}, fileName={}", s3Key, fileName, e);
            throw new S3PresignedUrlException("Failed to generate presigned URL: " + e.getMessage(), e);
        }
    }

    /**
     * Result containing presigned URL and expiration time.
     *
     * @param url       Presigned S3 URL
     * @param expiresAt URL expiration timestamp
     */
    public record PresignedUrlResult(String url, Instant expiresAt) {
    }

    /**
     * Exception thrown when presigned URL generation fails.
     */
    public static class S3PresignedUrlException extends RuntimeException {
        public S3PresignedUrlException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
