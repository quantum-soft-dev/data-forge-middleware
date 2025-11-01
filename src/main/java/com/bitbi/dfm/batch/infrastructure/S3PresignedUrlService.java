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
     * Generate presigned URL for S3 file download.
     * <p>
     * The generated URL allows direct download from S3 without authentication.
     * URL expires after 15 minutes.
     * </p>
     *
     * @param s3Key    S3 object key
     * @param fileName Suggested filename for Content-Disposition header
     * @return Presigned URL and expiration time
     * @throws IllegalArgumentException if s3Key or fileName is null/empty
     */
    public PresignedUrlResult generatePresignedUrl(String s3Key, String fileName) {
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("S3 key cannot be null or empty");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }

        logger.debug("Generating presigned URL for s3Key={}, fileName={}", s3Key, fileName);

        try {
            // Create GetObjectRequest with Content-Disposition hint
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .responseContentDisposition("attachment; filename=\"" + fileName + "\"")
                    .build();

            // Create presign request with 15-minute expiry
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGNED_URL_EXPIRY)
                    .getObjectRequest(getObjectRequest)
                    .build();

            // Generate presigned URL
            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            URL presignedUrl = presignedRequest.url();
            Instant expiresAt = Instant.now().plus(PRESIGNED_URL_EXPIRY);

            logger.info("Generated presigned URL for file={}, expiresAt={}", fileName, expiresAt);

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
