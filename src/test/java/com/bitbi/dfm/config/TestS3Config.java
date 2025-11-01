package com.bitbi.dfm.config;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test configuration for S3 services.
 * <p>
 * Provides mock S3 services that don't require LocalStack for contract tests.
 * </p>
 */
@TestConfiguration
public class TestS3Config {

    /**
     * Mock S3FileStorageService that doesn't actually upload to S3.
     * <p>
     * This allows tests to run without LocalStack.
     * Still calculates real checksums for validation.
     * </p>
     */
    @Bean
    @Primary
    public S3FileStorageService mockS3FileStorageService() {
        return new S3FileStorageService(null, "test-bucket") {
            @Override
            public String uploadFile(MultipartFile file, String s3Path, String fileName) {
                // Mock implementation - just calculate checksum without uploading
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] hash = digest.digest(file.getBytes());
                    return HexFormat.of().formatHex(hash);
                } catch (NoSuchAlgorithmException | IOException e) {
                    throw new RuntimeException("Failed to calculate checksum", e);
                }
            }
        };
    }

    /**
     * Mock S3PresignedUrlService for contract tests.
     * <p>
     * Returns fake presigned URLs without connecting to LocalStack.
     * </p>
     */
    @Bean
    @Primary
    public S3PresignedUrlService mockS3PresignedUrlService() {
        S3PresignedUrlService mock = mock(S3PresignedUrlService.class);
        when(mock.generatePresignedUrl(any(), any())).thenAnswer(invocation -> {
            String s3Key = invocation.getArgument(0);
            String fileName = invocation.getArgument(1);
            return new S3PresignedUrlService.PresignedUrlResult(
                    "http://localhost:4566/test-bucket/" + s3Key + "?X-Amz-Signature=mock",
                    Instant.now().plusSeconds(900) // 15 minutes
            );
        });
        return mock;
    }

    /**
     * Mock S3Client for contract tests.
     * <p>
     * Returns mock S3 client that doesn't connect to LocalStack.
     * Used by FileDownloadService for ZIP streaming.
     * </p>
     */
    @Bean
    @Primary
    public S3Client mockS3Client() {
        S3Client mock = mock(S3Client.class);

        // Mock getObject() to return fake file content
        // IMPORTANT: thenAnswer() creates a new stream for each invocation
        when(mock.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenAnswer(invocation -> {
                    // Return NEW input stream for each file (avoid stream reuse issues)
                    String mockCsvData = "id,name,value\n1,Test,123\n";
                    byte[] data = mockCsvData.getBytes();

                    // Wrap in ResponseInputStream to match AWS SDK return type
                    software.amazon.awssdk.services.s3.model.GetObjectResponse response =
                            software.amazon.awssdk.services.s3.model.GetObjectResponse.builder()
                                    .contentLength((long) data.length)
                                    .contentType("text/csv")
                                    .build();

                    return new software.amazon.awssdk.core.ResponseInputStream<>(
                            response,
                            new java.io.ByteArrayInputStream(data)
                    );
                });

        return mock;
    }
}
