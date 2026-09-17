package com.bitbi.dfm.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * T027: Redis caching configuration for upload history feature.
 * <p>
 * Configures multi-level caching with different TTLs:
 * - batch-first-page: 5-minute TTL (hot data, frequently accessed)
 * - batch-details: 30-minute TTL (only for COMPLETED batches)
 * </p>
 *
 * <p><strong>Performance Impact:</strong></p>
 * <ul>
 *   <li>First page cache hit reduces DB load by ~80%</li>
 *   <li>Completed batch details are immutable (safe to cache long-term)</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This configuration is only active when spring.cache.type != none.
 * In test profile, caching is disabled via spring.cache.type=none.</p>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class CacheConfiguration {

    /**
     * Prefix of every cache key, in front of Spring's {@code cacheName::} (issue #302).
     * <p>
     * The value serializer moved from Jackson 2 to Jackson 3 with Boot 4, and nothing guarantees that
     * one reads what the other wrote. During a rolling deployment old and new pods share this Redis,
     * so the new pods write under a key space the old ones never read and vice versa: an old entry
     * simply misses and expires. Change it whenever the stored form changes again.
     */
    public static final String KEY_PREFIX = "jackson3:";

    /**
     * T027: Configure Redis cache manager with per-cache TTL settings.
     *
     * @param connectionFactory Redis connection factory
     * @return Configured cache manager
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration (10-minute TTL)
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .computePrefixWith(cacheName -> KEY_PREFIX + cacheName + "::")
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(valueSerializer())
                );

        // Per-cache configurations
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // batch-first-page: Short TTL for list view (frequently changing data)
        cacheConfigurations.put("batch-first-page", defaultConfig
                .entryTtl(Duration.ofMinutes(5)));

        // batch-details: Longer TTL for completed batches (immutable data)
        cacheConfigurations.put("batch-details", defaultConfig
                .entryTtl(Duration.ofMinutes(30)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * Jackson 3 twin of the former {@code new GenericJackson2JsonRedisSerializer()}: type metadata on
     * every value so a cached object reads back as its own class, and Spring's cache null marker.
     * Default typing admits this application's types and the JDK's rather than the Jackson 2
     * serializer's accept-anything validator: a cache entry is data whoever can write to Redis
     * controls, and nothing the application caches is of any other type.
     */
    private static GenericJacksonJsonRedisSerializer valueSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.bitbi.dfm.")
                        .allowIfSubType("java.")
                        .build())
                .enableSpringCacheNullValueSupport()
                .build();
    }
}
