package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaTableStatsDto;
import com.bitbi.dfm.batch.presentation.dto.FileMetadataDto;
import com.bitbi.dfm.config.CacheConfiguration;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Characterization of the Redis cache ({@code CacheConfiguration}), written on Spring Boot 3.5 /
 * Jackson 2 before the upgrade (issue #300) and carried to Boot 4.1 / Jackson 3 by #302.
 * <p>
 * <b>What #302 changed here, deliberately:</b> the serializer is {@code GenericJacksonJsonRedisSerializer}
 * and every key carries {@link CacheConfiguration#KEY_PREFIX}, so pods of the two versions sharing one
 * Redis during a rolling deployment never read each other's entries. Two expectations moved with it:
 * the keys read below carry the prefix, and a {@code BatchDetailDto} — which the Jackson 2 serializer
 * could not write for want of JSR-310 — now round-trips, since Jackson 3 has {@code java.time} built in.
 * The stored form of the other values ({@code @class} metadata, the wrapper array of a non-final
 * value) is byte-for-byte what it was. What production caches is unchanged, for the reasons below.
 * </p>
 * <p>
 * The finding this class pins is that <b>neither cache holds a value in practice</b>, for two
 * independent reasons, and that the round trip the ticket asked about is therefore a property of
 * the serializer alone, not of anything production reads back today:
 * </p>
 * <ul>
 *   <li>{@code batch-details} ({@code BatchHistoryService.getBatchDetails}) declares
 *       {@code condition = "#result != null && ..."}. A {@code condition} is evaluated <em>before</em>
 *       the call, where {@code #result} is undefined, so it is false and the value is never stored.
 *       On Boot 3.5 the write would also have failed (no JSR-310 in the Jackson 2 serializer); since
 *       #302 it would succeed, which is why this condition alone now keeps the cache inert.</li>
 *   <li>{@code batch-first-page} ({@code fetchFirstPage}) is a {@code protected} method invoked on
 *       {@code this}, so the proxy never sees the call.</li>
 * </ul>
 * A Jackson 3 serializer that <em>can</em> write {@code Instant} does not change what production
 * caches as long as both conditions stand; a fix to either one makes the round trip below live and
 * turns these assertions into the decision to take.
 */
@DisplayName("#300/#302 — Redis cache serialization")
class RedisCacheSerializationIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID COMPLETED_BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private BatchHistoryService batchHistoryService;

    private static String key(String cacheName, Object id) {
        return CacheConfiguration.KEY_PREFIX + cacheName + "::" + id;
    }

    private byte[] readRaw(String key) {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            return connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The stored bytes, waited for. With Spring Data Redis 4 {@code Cache#put} can return before the
     * value is readable on another connection (observed while carrying this class to Boot 4.1: the
     * key appeared a moment after a first read found nothing), so one read races the write.
     */
    private byte[] rawValue(String key) {
        return await().atMost(Duration.ofSeconds(5)).until(() -> readRaw(key), java.util.Objects::nonNull);
    }

    /** Absence held for a moment rather than sampled once, for the same reason (#159's discipline). */
    private void assertStaysAbsent(String key) {
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> readRaw(key) == null);
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
    @DisplayName("BatchDetailDto round-trips: Jackson 3 writes java.time without a module (changed by #302)")
    void batchDetailRoundTrips() {
        UUID key = UUID.fromString("00000000-0000-0000-0000-000000000003");
        BatchDetailDto detail = new BatchDetailDto(key, UUID.fromString("00000000-0000-0000-0000-000000000004"),
                "COMPLETED", false, 1, 10L, Instant.parse("2026-01-15T10:30:00.123456Z"), null,
                List.of(new FileMetadataDto(UUID.fromString("00000000-0000-0000-0000-000000000005"), "a.csv", 5L,
                        Instant.parse("2026-01-15T10:31:00Z"))),
                List.of(DeltaTableStatsDto.of("orders", new TableChangeStats(1, 2, 3))),
                "DELTA", new DeltaSeqRangeDto(1, 9));
        Cache cache = cacheManager.getCache("batch-details");
        try {
            cache.put(key, detail);

            assertThat(new String(rawValue(key("batch-details", key)), StandardCharsets.UTF_8))
                    .isEqualTo("{\"@class\":\"com.bitbi.dfm.batch.presentation.dto.BatchDetailDto\","
                            + "\"id\":\"00000000-0000-0000-0000-000000000003\","
                            + "\"siteId\":\"00000000-0000-0000-0000-000000000004\",\"status\":\"COMPLETED\","
                            + "\"hasErrors\":false,\"uploadedFilesCount\":1,\"totalSize\":10,"
                            + "\"startedAt\":\"2026-01-15T10:30:00.123456Z\",\"completedAt\":null,"
                            + "\"files\":[\"java.util.ImmutableCollections$List12\",[{\"@class\":"
                            + "\"com.bitbi.dfm.batch.presentation.dto.FileMetadataDto\","
                            + "\"id\":\"00000000-0000-0000-0000-000000000005\",\"originalFileName\":\"a.csv\","
                            + "\"fileSize\":5,\"uploadedAt\":\"2026-01-15T10:31:00Z\"}]],"
                            + "\"deltaStats\":[\"java.util.ImmutableCollections$List12\",[{\"@class\":"
                            + "\"com.bitbi.dfm.batch.presentation.dto.DeltaTableStatsDto\",\"table\":\"orders\","
                            + "\"inserts\":1,\"updates\":2,\"deletes\":3}]],\"mode\":\"DELTA\","
                            + "\"seqRange\":{\"@class\":\"com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto\","
                            + "\"first\":1,\"last\":9}}");
            assertThat(cache.get(key).get()).isEqualTo(detail);
        } finally {
            cache.evict(key);
        }
    }

    @Test
    @DisplayName("an entry in the pre-#302 key space is not read: old and new pods never share entries")
    void entryWrittenWithoutThePrefixIsNotRead() {
        UUID key = UUID.randomUUID();
        byte[] legacyKey = ("batch-details::" + key).getBytes(StandardCharsets.UTF_8);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.stringCommands().set(legacyKey,
                    "{\"@class\":\"com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto\",\"first\":1,\"last\":2}"
                            .getBytes(StandardCharsets.UTF_8));
            try {
                assertThat(readRaw(new String(legacyKey, StandardCharsets.UTF_8))).isNotNull();
                assertThat(cacheManager.getCache("batch-details").get(key)).isNull();
            } finally {
                connection.keyCommands().del(legacyKey);
            }
        }
    }

    @Test
    @DisplayName("a record without java.time round-trips with @class metadata")
    void recordWithoutJavaTimeRoundTrips() {
        UUID key = UUID.randomUUID();
        Cache cache = cacheManager.getCache("batch-details");
        try {
            cache.put(key, new DeltaSeqRangeDto(1, 9_000_000_000L));

            assertThat(new String(rawValue(key("batch-details", key)), StandardCharsets.UTF_8))
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

            assertThat(new String(rawValue(key("batch-details", key)), StandardCharsets.UTF_8))
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
        assertStaysAbsent(key("batch-details", COMPLETED_BATCH_ID));
    }

    @Test
    @DisplayName("listBatchHistory never populates batch-first-page")
    void batchFirstPageIsNeverPopulated() {
        batchHistoryService.listBatchHistory(ACCOUNT_ID, null, 20);

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> keyCount(CacheConfiguration.KEY_PREFIX + "batch-first-page::*") == 0);
    }
}
