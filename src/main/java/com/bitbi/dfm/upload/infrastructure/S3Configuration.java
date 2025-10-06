package com.bitbi.dfm.upload.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * AWS S3 configuration with LocalStack support for dev/test environments.
 * <p>
 * Provides different S3Client configurations based on active profile:
 * - dev/test: LocalStack with hardcoded credentials
 * - prod: AWS with DefaultCredentialsProvider (IAM role)
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class S3Configuration {

    @Value("${s3.region:us-east-1}")
    private String region;

    /**
     * S3Client for dev/test environments using LocalStack.
     * <p>
     * LocalStack endpoint: http://localhost:4566
     * Uses test credentials: test/test
     * </p>
     */
    @Bean
    @Profile({"dev", "test"})
    public S3Client s3ClientLocalStack(
            @Value("${s3.endpoint:http://localhost:4566}") String endpoint,
            @Value("${s3.access-key:test}") String accessKey,
            @Value("${s3.secret-key:test}") String secretKey) {

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(true) // Required for LocalStack
                .build();
    }

    /**
     * S3Client for production environment using AWS.
     * <p>
     * Uses DefaultCredentialsProvider which resolves credentials from:
     * 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
     * 2. System properties (aws.accessKeyId, aws.secretAccessKey)
     * 3. Credentials file (~/.aws/credentials)
     * 4. IAM role (recommended for EC2/ECS/Lambda)
     * </p>
     */
    @Bean
    @Profile("prod")
    public S3Client s3ClientAws() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
