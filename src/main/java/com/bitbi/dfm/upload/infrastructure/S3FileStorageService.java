package com.bitbi.dfm.upload.infrastructure;

import com.bitbi.dfm.upload.domain.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
    private static final long RETRY_DELAY_MS = 1000;

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
                sleep(RETRY_DELAY_MS);

            } catch (IOException e) {
                throw new FileStorageException("Failed to read file content: " + fileName, e);
            }
        }

        throw new FileStorageException("Failed to upload file to S3: " + fileName);
    }

    /**
     * Calculate MD5 checksum for file.
     *
     * @param file multipart file
     * @return hex-encoded MD5 checksum
     * @throws FileStorageException if checksum calculation fails
     */
    public String calculateChecksum(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                md5.update(buffer, 0, bytesRead);
            }

            byte[] digest = md5.digest();
            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException e) {
            throw new FileStorageException("MD5 algorithm not available", e);
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
}
