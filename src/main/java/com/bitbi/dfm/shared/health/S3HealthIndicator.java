package com.bitbi.dfm.shared.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Custom health indicator for S3 connectivity.
 * <p>
 * Checks if the configured S3 bucket is accessible.
 * Used by Spring Boot Actuator health endpoint.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class S3HealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(S3HealthIndicator.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3HealthIndicator(
            S3Client s3Client,
            @Value("${s3.bucket.name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public Health health() {
        try {
            // Check bucket accessibility
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();

            s3Client.headBucket(headBucketRequest);

            logger.debug("S3 health check passed: bucket={}", bucketName);

            return Health.up()
                    .withDetail("bucket", bucketName)
                    .withDetail("status", "accessible")
                    .build();

        } catch (Exception e) {
            logger.error("S3 health check failed: bucket={}, error={}", bucketName, e.getMessage());

            return Health.down()
                    .withDetail("bucket", bucketName)
                    .withDetail("status", "inaccessible")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
