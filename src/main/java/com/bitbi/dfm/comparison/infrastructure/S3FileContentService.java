package com.bitbi.dfm.comparison.infrastructure;

import com.bitbi.dfm.upload.application.CsvDecompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Service for fetching file contents from S3 storage.
 *
 * <p>This service handles:
 * <ul>
 *   <li>Retrieving file contents from S3 by key</li>
 *   <li>Gzip decompression for .csv.gz files (Updated 2025-11-03)</li>
 *   <li>Encoding detection (defaults to UTF-8)</li>
 *   <li>Binary file detection</li>
 *   <li>Error handling for missing files and access issues</li>
 * </ul>
 */
@Service
public class S3FileContentService {

    private static final Logger log = LoggerFactory.getLogger(S3FileContentService.class);
    private static final int MAX_FILE_SIZE_MB = 100;
    private static final long MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024;

    private final S3Client s3Client;
    private final String bucketName;
    private final CsvDecompressionService decompressionService;

    public S3FileContentService(
        S3Client s3Client,
        @Value("${s3.bucket.name}") String bucketName,
        CsvDecompressionService decompressionService
    ) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.decompressionService = decompressionService;
    }

    /**
     * Fetches file content from S3 as a string.
     *
     * @param s3Key the S3 object key
     * @return file content as string
     * @throws FileNotFoundException if the file does not exist in S3
     * @throws FileAccessDeniedException if access to the file is denied
     * @throws FileTooLargeException if the file exceeds the maximum size limit
     * @throws BinaryFileException if the file is binary (not text)
     * @throws FileContentRetrievalException for other S3-related errors
     */
    public String fetchFileContent(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("S3 key cannot be null or blank");
        }

        log.debug("Fetching file content from S3: bucket={}, key={}", bucketName, s3Key);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

            ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(getObjectRequest);
            GetObjectResponse response = responseStream.response();

            // Check file size
            Long contentLength = response.contentLength();
            if (contentLength != null && contentLength > MAX_FILE_SIZE_BYTES) {
                throw new FileTooLargeException(
                    String.format("File size (%d bytes) exceeds maximum allowed (%d MB)",
                        contentLength, MAX_FILE_SIZE_MB)
                );
            }

            // Detect encoding (simplified - could use EncodingDetectionService from Upload History)
            Charset charset = detectCharset(response.contentType());

            // Check if binary (based on content type) - SKIP for gzip files since they'll be decompressed
            if (!isGzipFile(s3Key) && isBinaryContentType(response.contentType())) {
                throw new BinaryFileException("File has binary content type: " + response.contentType());
            }

            // Decompress gzip files if needed (Updated 2025-11-03)
            InputStream processedStream = decompressionService.decompressIfNeeded(responseStream, s3Key);

            // Read content
            String content = new BufferedReader(new InputStreamReader(processedStream, charset))
                .lines()
                .collect(Collectors.joining("\n"));

            log.debug("Successfully fetched file content: key={}, size={} bytes", s3Key, content.length());
            return content;

        } catch (IOException e) {
            log.error("IO error reading file from S3: bucket={}, key={}", bucketName, s3Key, e);
            throw new FileContentRetrievalException("Failed to read file content: " + s3Key, e);

        } catch (NoSuchKeyException e) {
            log.warn("File not found in S3: bucket={}, key={}", bucketName, s3Key);
            throw new FileNotFoundException("File not found: " + s3Key, e);

        } catch (S3Exception e) {
            if (e.statusCode() == 403) {
                log.error("Access denied to S3 file: bucket={}, key={}", bucketName, s3Key);
                throw new FileAccessDeniedException("Access denied to file: " + s3Key, e);
            }
            log.error("S3 error fetching file: bucket={}, key={}", bucketName, s3Key, e);
            throw new FileContentRetrievalException("Failed to fetch file from S3: " + s3Key, e);
        }
    }

    /**
     * Detects charset from content type.
     * Defaults to UTF-8 if not specified.
     */
    private Charset detectCharset(String contentType) {
        if (contentType != null && contentType.contains("charset=")) {
            String charsetName = contentType.substring(contentType.indexOf("charset=") + 8).trim();
            try {
                return Charset.forName(charsetName);
            } catch (Exception e) {
                log.warn("Unknown charset in content type: {}. Defaulting to UTF-8", charsetName);
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Checks if a file is gzip-compressed based on its key.
     * (Added 2025-11-03)
     */
    private boolean isGzipFile(String s3Key) {
        if (s3Key == null) {
            return false;
        }
        return s3Key.toLowerCase().endsWith(".gz");
    }

    /**
     * Checks if the content type indicates a binary file.
     */
    private boolean isBinaryContentType(String contentType) {
        if (contentType == null) {
            return false;  // Assume text if not specified
        }

        String lowerContentType = contentType.toLowerCase();
        return lowerContentType.startsWith("application/octet-stream") ||
               lowerContentType.startsWith("image/") ||
               lowerContentType.startsWith("video/") ||
               lowerContentType.startsWith("audio/") ||
               lowerContentType.contains("binary");
    }

    // Custom exceptions
    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class FileAccessDeniedException extends RuntimeException {
        public FileAccessDeniedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(String message) {
            super(message);
        }
    }

    public static class BinaryFileException extends RuntimeException {
        public BinaryFileException(String message) {
            super(message);
        }
    }

    public static class FileContentRetrievalException extends RuntimeException {
        public FileContentRetrievalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
