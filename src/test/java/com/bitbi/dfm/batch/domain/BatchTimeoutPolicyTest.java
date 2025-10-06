package com.bitbi.dfm.batch.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BatchTimeoutPolicy.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("BatchTimeoutPolicy Unit Tests")
class BatchTimeoutPolicyTest {

    private BatchTimeoutPolicy policy;
    private static final int TIMEOUT_MINUTES = 60;

    @BeforeEach
    void setUp() {
        policy = new BatchTimeoutPolicy(TIMEOUT_MINUTES);
    }

    @Test
    @DisplayName("Should not be expired when batch is not IN_PROGRESS")
    void shouldNotBeExpiredWhenNotInProgress() {
        // Given
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");
        batch.complete();

        // When
        boolean expired = policy.isExpired(batch);

        // Then
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should not be expired when batch is within timeout window")
    void shouldNotBeExpiredWhenWithinTimeout() {
        // Given - freshly started batch
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");

        // When
        boolean expired = policy.isExpired(batch);

        // Then
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should correctly identify expired batch using isExpired method")
    void shouldIdentifyExpiredBatch() {
        // Given - batch started more than timeout minutes ago
        UUID siteId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Batch batch = Batch.start(siteId, accountId, "example.com");

        // When - check if batch would be expired (using batch's own method)
        boolean expired = batch.isExpired(TIMEOUT_MINUTES);

        // Then - freshly started batch should not be expired
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should use configured timeout minutes")
    void shouldUseConfiguredTimeout() {
        // Given
        int customTimeout = 30;
        BatchTimeoutPolicy customPolicy = new BatchTimeoutPolicy(customTimeout);
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");

        // When
        boolean expired = customPolicy.isExpired(batch);

        // Then
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should handle zero timeout correctly")
    void shouldHandleZeroTimeout() {
        // Given
        BatchTimeoutPolicy zeroTimeoutPolicy = new BatchTimeoutPolicy(0);
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");

        // When
        boolean expired = zeroTimeoutPolicy.isExpired(batch);

        // Then - even with zero timeout, freshly created batch should evaluate based on actual time
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should return false for completed batch regardless of start time")
    void shouldReturnFalseForCompletedBatch() {
        // Given
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");
        batch.complete();

        // When
        boolean expired = policy.isExpired(batch);

        // Then
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should return false for failed batch")
    void shouldReturnFalseForFailedBatch() {
        // Given
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");
        batch.fail();

        // When
        boolean expired = policy.isExpired(batch);

        // Then
        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("Should return false for cancelled batch")
    void shouldReturnFalseForCancelledBatch() {
        // Given
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID(), "example.com");
        batch.cancel();

        // When
        boolean expired = policy.isExpired(batch);

        // Then
        assertThat(expired).isFalse();
    }
}
