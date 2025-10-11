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
            throw new IllegalArgumentException("maxConcurrentBatches must be at least 1");
        }
        this.maxConcurrentBatches = maxConcurrentBatches;
    }
}
