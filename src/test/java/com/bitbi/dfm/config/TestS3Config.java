package com.bitbi.dfm.config;

import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Test configuration for S3 services.
 * <p>
 * Provides mock S3FileStorageService that doesn't require LocalStack.
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
}
