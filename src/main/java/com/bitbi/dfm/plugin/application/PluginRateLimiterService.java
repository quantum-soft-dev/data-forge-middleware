package com.bitbi.dfm.plugin.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Rate limiting service for Plugin API endpoints.
 * <p>
 * Implements per-account rate limiting using Token Bucket algorithm (Bucket4j).
 * Default limit: 100 requests per minute per account.
 * </p>
 * <p>
 * Uses Caffeine cache with 1-hour TTL to prevent memory leaks from
 * accumulating buckets for inactive/deleted accounts.
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
     * Reinit rate limit: 1 request per 30 seconds.
     * Reinit is resource-intensive (S3 deletions, SQL generation).
     */
    private static final int REINIT_COOLDOWN_SECONDS = 30;

    /**
     * TTL for bucket cache entries (1 hour).
     * Buckets for inactive accounts are automatically evicted after this duration.
     */
    private static final Duration BUCKET_TTL = Duration.ofHours(1);

    /**
     * Maximum number of cached buckets to prevent unbounded growth.
     */
    private static final int MAX_CACHED_BUCKETS = 10_000;

    /**
     * Cache of rate limit buckets per account ID.
     * Uses Caffeine with TTL and size limits to prevent memory leaks.
     */
    private final Cache<UUID, Bucket> buckets;

    /**
     * Separate cache for reinit rate limit buckets.
     * Has longer TTL since reinit cooldown is 30 seconds.
     */
    private final Cache<UUID, Bucket> reinitBuckets;

    private final MeterRegistry meterRegistry;

    public PluginRateLimiterService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_CACHED_BUCKETS)
                .recordStats()
                .build();

        this.reinitBuckets = Caffeine.newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_CACHED_BUCKETS)
                .recordStats()
                .build();

        // Register cache metrics
        meterRegistry.gauge("plugin.api.rate.limiter.cache.size", buckets, Cache::estimatedSize);
        meterRegistry.gauge("plugin.api.rate.limiter.reinit.cache.size", reinitBuckets, Cache::estimatedSize);
    }

    /**
     * Attempts to consume a token for the given account.
     * Returns true if the request is allowed, false if rate limited.
     *
     * @param accountId The account making the request
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsume(UUID accountId) {
        Bucket bucket = buckets.get(accountId, this::createBucket);
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
     * Attempts to consume a token for reinit operation.
     * Reinit has a stricter rate limit: 1 request per 30 seconds.
     *
     * @param accountId The account making the request
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryConsumeReinit(UUID accountId) {
        Bucket bucket = reinitBuckets.get(accountId, this::createReinitBucket);
        boolean allowed = bucket.tryConsume(1);

        if (allowed) {
            meterRegistry.counter("plugin.api.reinit.rate.limit.allowed", "accountId", accountId.toString()).increment();
        } else {
            meterRegistry.counter("plugin.api.reinit.rate.limit.exceeded", "accountId", accountId.toString()).increment();
            log.warn("Reinit rate limit exceeded for accountId={}", accountId);
        }

        return allowed;
    }

    /**
     * Gets the retry-after duration for reinit rate limiting.
     *
     * @return The reinit cooldown in seconds
     */
    public int getReinitRetryAfterSeconds() {
        return REINIT_COOLDOWN_SECONDS;
    }

    /**
     * Gets the number of available tokens for an account (for Retry-After header).
     *
     * @param accountId The account ID
     * @return Estimated time in seconds until next token is available
     */
    public long getRetryAfterSeconds(UUID accountId) {
        Bucket bucket = buckets.getIfPresent(accountId);
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
     * Creates a new rate limit bucket for reinit operations.
     * 1 request per 30 seconds, with interval refill.
     */
    private Bucket createReinitBucket(UUID accountId) {
        Bandwidth limit = Bandwidth.classic(
                1, // Only 1 token
                Refill.intervally(1, Duration.ofSeconds(REINIT_COOLDOWN_SECONDS))
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
        buckets.invalidate(accountId);
        reinitBuckets.invalidate(accountId);
    }

    /**
     * Gets the current bucket count (for monitoring).
     */
    public long getBucketCount() {
        return buckets.estimatedSize();
    }

    /**
     * Invalidates all cached buckets (useful for testing).
     */
    public void invalidateAll() {
        buckets.invalidateAll();
        reinitBuckets.invalidateAll();
    }
}
