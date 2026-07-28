package com.bitbi.dfm.config;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Instant;

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
