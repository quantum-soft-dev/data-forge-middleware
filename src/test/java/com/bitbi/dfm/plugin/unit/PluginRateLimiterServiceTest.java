package com.bitbi.dfm.plugin.unit;

import com.bitbi.dfm.plugin.application.PluginRateLimiterService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PluginRateLimiterService.
 *
 * <p>Tests rate limiting functionality for plugin API endpoints,
 * including standard rate limits and reinit-specific limits.</p>
 */
@DisplayName("PluginRateLimiterService")
class PluginRateLimiterServiceTest {

    private PluginRateLimiterService rateLimiterService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        rateLimiterService = new PluginRateLimiterService(meterRegistry);
    }

    @Nested
    @DisplayName("tryConsume()")
    class TryConsume {

        @Test
        @DisplayName("Should allow first request")
        void shouldAllowFirstRequest() {
            // Given
            UUID accountId = UUID.randomUUID();

            // When
            boolean allowed = rateLimiterService.tryConsume(accountId);

            // Then
            assertThat(allowed).isTrue();
        }

        @Test
        @DisplayName("Should allow multiple requests within limit")
        void shouldAllowMultipleRequestsWithinLimit() {
            // Given
            UUID accountId = UUID.randomUUID();

            // When - make 50 requests (within 100/min limit)
            for (int i = 0; i < 50; i++) {
                boolean allowed = rateLimiterService.tryConsume(accountId);
                assertThat(allowed).as("Request %d should be allowed", i).isTrue();
            }
        }

        @Test
        @DisplayName("Should rate limit after exceeding limit")
        void shouldRateLimitAfterExceedingLimit() {
            // Given
            UUID accountId = UUID.randomUUID();

            // When - exhaust all 100 tokens
            for (int i = 0; i < 100; i++) {
                rateLimiterService.tryConsume(accountId);
            }

            // Then - 101st request should be rate limited
            boolean allowed = rateLimiterService.tryConsume(accountId);
            assertThat(allowed).isFalse();
        }
    }

    @Nested
    @DisplayName("tryConsumeReinit() - Feature 015")
    class TryConsumeReinit {

        @Test
        @DisplayName("Should allow first reinit request")
        void shouldAllowFirstReinitRequest() {
            // Given
            UUID accountId = UUID.randomUUID();

            // When
            boolean allowed = rateLimiterService.tryConsumeReinit(accountId);

            // Then
            assertThat(allowed).isTrue();
        }

        @Test
        @DisplayName("Should rate limit second reinit request immediately")
        void shouldRateLimitSecondReinitRequestImmediately() {
            // Given
            UUID accountId = UUID.randomUUID();

            // When - first request
            rateLimiterService.tryConsumeReinit(accountId);

            // Then - second request should be rate limited (only 1 token per 30 sec)
            boolean allowed = rateLimiterService.tryConsumeReinit(accountId);
            assertThat(allowed).isFalse();
        }

        @Test
        @DisplayName("Should allow different accounts to reinit independently")
        void shouldAllowDifferentAccountsToReinitIndependently() {
            // Given
            UUID accountId1 = UUID.randomUUID();
            UUID accountId2 = UUID.randomUUID();

            // When
            boolean allowed1 = rateLimiterService.tryConsumeReinit(accountId1);
            boolean allowed2 = rateLimiterService.tryConsumeReinit(accountId2);

            // Then - both should be allowed (separate buckets)
            assertThat(allowed1).isTrue();
            assertThat(allowed2).isTrue();
        }

        @Test
        @DisplayName("Should not affect standard rate limit")
        void shouldNotAffectStandardRateLimit() {
            // Given
            UUID accountId = UUID.randomUUID();

            // When - use reinit token
            rateLimiterService.tryConsumeReinit(accountId);

            // Then - standard rate limit should still be available
            boolean standardAllowed = rateLimiterService.tryConsume(accountId);
            assertThat(standardAllowed).isTrue();
        }
    }

    @Nested
    @DisplayName("getReinitRetryAfterSeconds()")
    class GetReinitRetryAfterSeconds {

        @Test
        @DisplayName("Should return 30 seconds")
        void shouldReturn30Seconds() {
            // When
            int retryAfter = rateLimiterService.getReinitRetryAfterSeconds();

            // Then
            assertThat(retryAfter).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("resetBucket()")
    class ResetBucket {

        @Test
        @DisplayName("Should reset standard rate limit")
        void shouldResetStandardRateLimit() {
            // Given
            UUID accountId = UUID.randomUUID();

            // Exhaust all tokens
            for (int i = 0; i < 100; i++) {
                rateLimiterService.tryConsume(accountId);
            }
            assertThat(rateLimiterService.tryConsume(accountId)).isFalse();

            // When - reset
            rateLimiterService.resetBucket(accountId);

            // Then - should be allowed again
            assertThat(rateLimiterService.tryConsume(accountId)).isTrue();
        }

        @Test
        @DisplayName("Should reset reinit rate limit")
        void shouldResetReinitRateLimit() {
            // Given
            UUID accountId = UUID.randomUUID();

            // Use the reinit token
            rateLimiterService.tryConsumeReinit(accountId);
            assertThat(rateLimiterService.tryConsumeReinit(accountId)).isFalse();

            // When - reset
            rateLimiterService.resetBucket(accountId);

            // Then - should be allowed again
            assertThat(rateLimiterService.tryConsumeReinit(accountId)).isTrue();
        }
    }

    @Nested
    @DisplayName("invalidateAll()")
    class InvalidateAll {

        @Test
        @DisplayName("Should reset all buckets")
        void shouldResetAllBuckets() {
            // Given
            UUID accountId1 = UUID.randomUUID();
            UUID accountId2 = UUID.randomUUID();

            // Exhaust tokens for both accounts
            for (int i = 0; i < 100; i++) {
                rateLimiterService.tryConsume(accountId1);
                rateLimiterService.tryConsume(accountId2);
            }
            rateLimiterService.tryConsumeReinit(accountId1);
            rateLimiterService.tryConsumeReinit(accountId2);

            // When
            rateLimiterService.invalidateAll();

            // Then - all should be allowed again
            assertThat(rateLimiterService.tryConsume(accountId1)).isTrue();
            assertThat(rateLimiterService.tryConsume(accountId2)).isTrue();
            assertThat(rateLimiterService.tryConsumeReinit(accountId1)).isTrue();
            assertThat(rateLimiterService.tryConsumeReinit(accountId2)).isTrue();
        }
    }
}
