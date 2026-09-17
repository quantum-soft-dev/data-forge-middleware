package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaTableStatsDto;
import com.bitbi.dfm.batch.presentation.dto.FileMetadataDto;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterization of the Redis cache ({@code CacheConfiguration}, {@code GenericJackson2JsonRedisSerializer})
 * on Spring Boot 3.5 / Jackson 2, before #302 replaces the serializer with a Jackson 3 one (issue #300).
 * <p>
 * The finding this class pins is that <b>neither cache holds a value in practice</b>, for two
 * independent reasons, and that the round trip the ticket asked about is therefore a property of
 * the serializer alone, not of anything production reads back today:
 * </p>
 * <ul>
 *   <li>{@code batch-details} ({@code BatchHistoryService.getBatchDetails}) declares
 *       {@code condition = "#result != null && ..."}. A {@code condition} is evaluated <em>before</em>
 *       the call, where {@code #result} is undefined, so it is false and the value is never stored.
 *       If it were stored, the write would fail: the serializer's own mapper has no JSR-310 module,
 *       and {@code BatchDetailDto} carries {@code Instant}s.</li>
 *   <li>{@code batch-first-page} ({@code fetchFirstPage}) is a {@code protected} method invoked on
 *       {@code this}, so the proxy never sees the call.</li>
 * </ul>
 * A Jackson 3 serializer that <em>can</em> write {@code Instant} does not change what production
 * caches as long as both conditions stand; a fix to either one makes the round trip below live and
 * turns these assertions into the decision to take.
 */
@DisplayName("#300 — Redis cache serialization (Boot 3.5 characterization)")
class RedisCacheSerializationIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID COMPLETED_BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private BatchHistoryService batchHistoryService;

    private byte[] rawValue(String key) {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            return connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
        }
    }

    private long keyCount(String pattern) {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            return connection.keyCommands().keys(pattern.getBytes(StandardCharsets.UTF_8)).size();
        }
    }

    @Test
    @DisplayName("the cache manager is the Redis one this class is about")
    void cacheManagerIsRedis() {
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
    }

    @Test
    @DisplayName("BatchDetailDto cannot be written: the serializer's mapper has no java.time support")
    void batchDetailCannotBeWritten() {
        UUID key = UUID.randomUUID();
        BatchDetailDto detail = new BatchDetailDto(key, UUID.randomUUID(), "COMPLETED", false, 1, 10L,
                Instant.parse("2026-01-15T10:30:00.123456Z"), null,
                List.of(new FileMetadataDto(UUID.randomUUID(), "a.csv", 5L, Instant.parse("2026-01-15T10:31:00Z"))),
                List.of(DeltaTableStatsDto.of("orders", new TableChangeStats(1, 2, 3))),
                "DELTA", new DeltaSeqRangeDto(1, 9));
        Cache cache = cacheManager.getCache("batch-details");

        assertThatThrownBy(() -> cache.put(key, detail))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("java.time.Instant");
        assertThat(rawValue("batch-details::" + key)).isNull();
    }

    @Test
    @DisplayName("a record without java.time round-trips with @class metadata")
    void recordWithoutJavaTimeRoundTrips() {
        UUID key = UUID.randomUUID();
        Cache cache = cacheManager.getCache("batch-details");
        try {
            cache.put(key, new DeltaSeqRangeDto(1, 9_000_000_000L));

            assertThat(new String(rawValue("batch-details::" + key), StandardCharsets.UTF_8))
                    .isEqualTo("{\"@class\":\"com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto\",\"first\":1,\"last\":9000000000}");
            assertThat(cache.get(key).get()).isEqualTo(new DeltaSeqRangeDto(1, 9_000_000_000L));
        } finally {
            cache.evict(key);
        }
    }

    @Test
    @DisplayName("a non-final value keeps its type: a List<String> round-trips with @class metadata")
    void nonFinalValueRoundTrips() {
        UUID key = UUID.randomUUID();
        Cache cache = cacheManager.getCache("batch-details");
        try {
            cache.put(key, new java.util.ArrayList<>(List.of("a", "b")));

            assertThat(new String(rawValue("batch-details::" + key), StandardCharsets.UTF_8))
                    .isEqualTo("[\"java.util.ArrayList\",[\"a\",\"b\"]]");
            assertThat(cache.get(key).get()).isInstanceOf(java.util.ArrayList.class).isEqualTo(List.of("a", "b"));
        } finally {
            cache.evict(key);
        }
    }

    @Test
    @DisplayName("getBatchDetails of a COMPLETED batch never populates batch-details")
    void batchDetailsIsNeverPopulated() {
        Cache cache = cacheManager.getCache("batch-details");
        cache.evict(COMPLETED_BATCH_ID);

        BatchDetailDto detail = batchHistoryService.getBatchDetails(COMPLETED_BATCH_ID, ACCOUNT_ID);
        batchHistoryService.getBatchDetails(COMPLETED_BATCH_ID, ACCOUNT_ID);

        assertThat(detail.status()).isEqualTo("COMPLETED");
        assertThat(cache.get(COMPLETED_BATCH_ID)).isNull();
        assertThat(rawValue("batch-details::" + COMPLETED_BATCH_ID)).isNull();
    }

    @Test
    @DisplayName("listBatchHistory never populates batch-first-page")
    void batchFirstPageIsNeverPopulated() {
        batchHistoryService.listBatchHistory(ACCOUNT_ID, null, 20);

        assertThat(keyCount("batch-first-page::*")).isZero();
    }
}
