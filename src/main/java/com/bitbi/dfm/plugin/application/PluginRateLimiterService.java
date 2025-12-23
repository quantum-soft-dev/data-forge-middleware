package com.bitbi.dfm.plugin.application;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting service for Plugin API endpoints.
 * <p>
 * Implements per-account rate limiting using Token Bucket algorithm (Bucket4j).
 * Default limit: 100 requests per minute per account.
 * </p>
 * <p>
 * Security: Prevents API abuse and DoS attacks by limiting request rate.
 * </p>
 */
@Service
public class PluginRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(PluginRateLimiterService.class);

    /**
     * Default rate limit: 100 requests per minute
     */
    private static final int DEFAULT_REQUESTS_PER_MINUTE = 100;

    /**
     * Cache of rate limit buckets per account ID
     */
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;

    public PluginRateLimiterService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Attempts to consume a token for the given account.
     * Returns true if the request is allowed, false if rate limited.
     *
     * @param accountId The account making the request
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsume(UUID accountId) {
        Bucket bucket = buckets.computeIfAbsent(accountId, this::createBucket);
        boolean allowed = bucket.tryConsume(1);

        if (allowed) {
            meterRegistry.counter("plugin.api.rate.limit.allowed", "accountId", accountId.toString()).increment();
        } else {
            meterRegistry.counter("plugin.api.rate.limit.exceeded", "accountId", accountId.toString()).increment();
            log.warn("Rate limit exceeded for accountId={}", accountId);
        }

        return allowed;
    }

    /**
     * Gets the number of available tokens for an account (for Retry-After header).
     *
     * @param accountId The account ID
     * @return Estimated time in seconds until next token is available
     */
    public long getRetryAfterSeconds(UUID accountId) {
        Bucket bucket = buckets.get(accountId);
        if (bucket == null) {
            return 0;
        }
        // Estimate based on refill rate (1 token per 0.6 seconds for 100/min)
        long availableTokens = bucket.getAvailableTokens();
        if (availableTokens > 0) {
            return 0;
        }
        // Return time until next refill (approximately)
        return 1; // At least 1 second
    }

    /**
     * Creates a new rate limit bucket with default configuration.
     * 100 requests per minute, with gradual refill.
     */
    private Bucket createBucket(UUID accountId) {
        Bandwidth limit = Bandwidth.classic(
                DEFAULT_REQUESTS_PER_MINUTE,
                Refill.greedy(DEFAULT_REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
        );
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * Clears the rate limit bucket for an account (useful for testing).
     *
     * @param accountId The account ID to reset
     */
    public void resetBucket(UUID accountId) {
        buckets.remove(accountId);
    }

    /**
     * Gets the current bucket count (for monitoring).
     */
    public int getBucketCount() {
        return buckets.size();
    }
}
