package com.bitbi.dfm.account.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Account domain.
 * <p>
 * Externalizes business rules and limits to application.yml for easy tuning
 * without code changes.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
@ConfigurationProperties(prefix = "account")
public class AccountProperties {

    /**
     * Configuration key for {@link #maxConcurrentBatches} — the name the refusal quotes, so a
     * crash-loop line says what to fix (issue #251). Relaxed binding still accepts the env var
     * {@code ACCOUNT_MAX_CONCURRENT_BATCHES}.
     */
    public static final String MAX_CONCURRENT_BATCHES_KEY = "account.max-concurrent-batches";

    /**
     * Maximum number of concurrent active batches allowed per account.
     * <p>
     * Business Rule: Prevents resource exhaustion by limiting parallel uploads.
     * Default: 5
     * </p>
     */
    private int maxConcurrentBatches = 5;

    public int getMaxConcurrentBatches() {
        return maxConcurrentBatches;
    }

    public void setMaxConcurrentBatches(int maxConcurrentBatches) {
        if (maxConcurrentBatches < 1) {
            throw new IllegalArgumentException(MAX_CONCURRENT_BATCHES_KEY
                    + " must be at least 1, but was " + maxConcurrentBatches
                    + ". Refusing to start: a non-positive limit would forbid every batch (issue #251).");
        }
        this.maxConcurrentBatches = maxConcurrentBatches;
    }
}
