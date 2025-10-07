package com.bitbi.dfm.shared.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for S3HealthIndicator.
 */
@DisplayName("S3HealthIndicator Unit Tests")
class S3HealthIndicatorTest {

    private S3Client s3Client;
    private S3HealthIndicator healthIndicator;
    private static final String BUCKET_NAME = "test-bucket";

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        healthIndicator = new S3HealthIndicator(s3Client, BUCKET_NAME);
    }

    @Test
    @DisplayName("Should return UP when bucket is accessible")
    void shouldReturnUpWhenBucketIsAccessible() {
        // Given: S3 bucket is accessible
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        // When
        Health health = healthIndicator.health();

        // Then
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals(BUCKET_NAME, health.getDetails().get("bucket"));
        assertEquals("accessible", health.getDetails().get("status"));
        verify(s3Client, times(1)).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    @DisplayName("Should return DOWN when bucket does not exist")
    void shouldReturnDownWhenBucketDoesNotExist() {
        // Given: S3 bucket does not exist
        NoSuchBucketException exception = (NoSuchBucketException) NoSuchBucketException.builder()
                .message("The specified bucket does not exist")
                .build();
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenThrow(exception);

        // When
        Health health = healthIndicator.health();

        // Then
        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(BUCKET_NAME, health.getDetails().get("bucket"));
        assertEquals("inaccessible", health.getDetails().get("status"));
        assertNotNull(health.getDetails().get("error"));
        assertTrue(health.getDetails().get("error").toString().contains("does not exist"));
        verify(s3Client, times(1)).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    @DisplayName("Should return DOWN when S3 client throws exception")
    void shouldReturnDownWhenS3ClientThrowsException() {
        // Given: S3 client throws generic exception
        RuntimeException exception = new RuntimeException("Connection timeout");
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenThrow(exception);

        // When
        Health health = healthIndicator.health();

        // Then
        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(BUCKET_NAME, health.getDetails().get("bucket"));
        assertEquals("inaccessible", health.getDetails().get("status"));
        assertEquals("Connection timeout", health.getDetails().get("error"));
        verify(s3Client, times(1)).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    @DisplayName("Should include bucket name in health details")
    void shouldIncludeBucketNameInHealthDetails() {
        // Given
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);

        // When
        Health health = healthIndicator.health();

        // Then
        assertNotNull(health.getDetails());
        assertTrue(health.getDetails().containsKey("bucket"));
        assertEquals(BUCKET_NAME, health.getDetails().get("bucket"));
    }
}
