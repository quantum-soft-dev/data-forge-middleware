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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 client/presigner configuration.
 * <p>
 * Provides different beans based on active profile:
 * <ul>
 *   <li><b>dev/test</b>: LocalStack with hardcoded credentials and path-style access.</li>
 *   <li><b>prod</b>: configurable object storage. The same beans target either real AWS S3
 *       or a GCS S3-compatible endpoint, driven entirely by configuration
 *       (endpoint, credentials, path-style, checksum) — no profile change required.</li>
 * </ul>
 * <p>
 * GCS S3-compatibility (XML API) requires: a custom endpoint override
 * ({@code https://storage.googleapis.com}), static HMAC credentials, path-style addressing,
 * and disabling AWS chunked-encoding / response checksum validation (GCS rejects the
 * {@code aws-chunked} / flexible-checksum payloads AWS sends by default).
 * </p>
 *
 * @author Data Forge Team
 * @version 1.1.0
 */
@Configuration
public class S3Configuration {

    @Value("${s3.region:us-east-1}")
    private String region;

    @Value("${s3.endpoint:}")
    private String endpoint;

    @Value("${s3.access-key:}")
    private String accessKey;

    @Value("${s3.secret-key:}")
    private String secretKey;

    @Value("${s3.path-style-access-enabled:false}")
    private boolean pathStyleAccessEnabled;

    @Value("${s3.checksum.enabled:true}")
    private boolean checksumEnabled;

    /**
     * S3Client for dev/test environments using LocalStack.
     * <p>
     * LocalStack endpoint: http://localhost:4566, test credentials test/test, path-style required.
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
     * S3Client for production object storage (AWS S3 or GCS S3-compatible), configured by properties.
     * <p>
     * Credentials: static keys when {@code s3.access-key}/{@code s3.secret-key} are set
     * (GCS HMAC or AWS keys); otherwise the AWS default credentials chain (IAM role) — preserving
     * the previous AWS-only behavior. The endpoint is overridden only for non-AWS endpoints.
     * </p>
     */
    @Bean
    @Profile("prod")
    public S3Client s3ClientAws() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(resolveCredentials(accessKey, secretKey))
                .serviceConfiguration(serviceConfiguration(pathStyleAccessEnabled, checksumEnabled));

        if (shouldOverrideEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }

    /**
     * S3Presigner for dev/test environments using LocalStack.
     * <p>
     * IMPORTANT: LocalStack requires path-style URLs (bucket in path, not subdomain).
     * </p>
     */
    @Bean
    @Profile({"dev", "test"})
    public S3Presigner s3PresignerLocalStack(
            @Value("${s3.endpoint:http://localhost:4566}") String endpoint,
            @Value("${s3.access-key:test}") String accessKey,
            @Value("${s3.secret-key:test}") String secretKey) {

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Presigner.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // Required for LocalStack presigned URLs
                        .build())
                .build();
    }

    /**
     * S3Presigner for production object storage (AWS S3 or GCS S3-compatible).
     * <p>
     * Must use the same endpoint / path-style / credentials as {@link #s3ClientAws()},
     * otherwise the SigV4 signature host will not match and the storage backend returns 403.
     * </p>
     */
    @Bean
    @Profile("prod")
    public S3Presigner s3PresignerAws() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(resolveCredentials(accessKey, secretKey))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccessEnabled)
                        .build());

        if (shouldOverrideEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }

    // --- Decision logic (package-private for unit testing) -------------------------------------

    /**
     * Selects the credentials provider: static credentials when both keys are non-blank
     * (GCS HMAC or explicit AWS keys), otherwise the AWS default chain (IAM role / env).
     */
    static AwsCredentialsProvider resolveCredentials(String accessKey, String secretKey) {
        if (isNotBlank(accessKey) && isNotBlank(secretKey)) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    /**
     * Whether to apply an explicit endpoint override. True only for a non-blank, non-AWS endpoint
     * (e.g. GCS); canonical AWS endpoints rely on the SDK's default endpoint resolution.
     */
    static boolean shouldOverrideEndpoint(String endpoint) {
        return isNotBlank(endpoint) && !endpoint.contains("amazonaws.com");
    }

    /**
     * Builds the S3 service configuration. For GCS ({@code checksumEnabled == false}) it disables
     * chunked-encoding and checksum validation, which the GCS S3-compatible XML API rejects.
     */
    static software.amazon.awssdk.services.s3.S3Configuration serviceConfiguration(
            boolean pathStyleAccessEnabled, boolean checksumEnabled) {
        software.amazon.awssdk.services.s3.S3Configuration.Builder builder =
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccessEnabled);
        if (!checksumEnabled) {
            builder.chunkedEncodingEnabled(false)
                    .checksumValidationEnabled(false);
        }
        return builder.build();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
