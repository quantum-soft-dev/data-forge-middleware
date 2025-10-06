package com.bitbi.dfm.batch.domain;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain service for batch timeout calculation and validation.
 * <p>
 * Encapsulates business logic for determining if a batch has exceeded
 * the configured timeout period and should be marked as NOT_COMPLETED.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class BatchTimeoutPolicy {

    private static final int DEFAULT_TIMEOUT_MINUTES = 60;

    private final int timeoutMinutes;

    /**
     * Create BatchTimeoutPolicy with default timeout (60 minutes).
     */
    public BatchTimeoutPolicy() {
        this.timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
    }

    /**
     * Create BatchTimeoutPolicy with custom timeout.
     *
     * @param timeoutMinutes timeout duration in minutes
     * @throws IllegalArgumentException if timeout is not positive
     */
    public BatchTimeoutPolicy(int timeoutMinutes) {
        if (timeoutMinutes <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Check if batch has exceeded timeout period.
     * <p>
     * A batch is considered expired if:
     * <ul>
     *   <li>Status is IN_PROGRESS</li>
     *   <li>Current time - startedAt > timeout</li>
     * </ul>
     * </p>
     *
     * @param batch batch to check
     * @return true if batch should be marked as NOT_COMPLETED
     */
    public boolean isExpired(Batch batch) {
        Objects.requireNonNull(batch, "Batch cannot be null");

        if (batch.getStatus() != BatchStatus.IN_PROGRESS) {
            return false;
        }

        LocalDateTime expirationTime = batch.getStartedAt().plusMinutes(timeoutMinutes);
        return LocalDateTime.now().isAfter(expirationTime);
    }

    /**
     * Calculate remaining time before batch expires.
     *
     * @param batch batch to calculate for
     * @return remaining minutes (0 if already expired or not IN_PROGRESS)
     */
    public long getRemainingMinutes(Batch batch) {
        Objects.requireNonNull(batch, "Batch cannot be null");

        if (batch.getStatus() != BatchStatus.IN_PROGRESS) {
            return 0;
        }

        LocalDateTime expirationTime = batch.getStartedAt().plusMinutes(timeoutMinutes);
        LocalDateTime now = LocalDateTime.now();

        if (now.isAfter(expirationTime)) {
            return 0;
        }

        return java.time.Duration.between(now, expirationTime).toMinutes();
    }

    /**
     * Calculate cutoff time for batch expiration queries.
     * <p>
     * Returns: current time - timeout duration
     * </p>
     *
     * @return cutoff timestamp for finding expired batches
     */
    public LocalDateTime calculateCutoffTime() {
        return LocalDateTime.now().minusMinutes(timeoutMinutes);
    }

    /**
     * Get configured timeout in minutes.
     *
     * @return timeout duration
     */
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }
}
