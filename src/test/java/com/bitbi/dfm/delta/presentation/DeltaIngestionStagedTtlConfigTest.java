package com.bitbi.dfm.delta.presentation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 030 (T03) — the staged-session TTL must stay inside the batch timeout.
 *
 * <p>The shipped defaults used to be inverted: {@code delta.ingestion.staged-ttl-millis} = 65 min
 * against a 60-min {@code batch.timeout.minutes}. In the 5-minute window between them a staged
 * session was still resumable while the timeout sweeper had already reaped its batch into
 * NOT_COMPLETED — the resume re-attached and the session failed late, at the final commit. The
 * guard in {@code DeltaIngestionService.onSessionStart} handles the case; these assertions keep the
 * defaults from drifting back into producing it routinely.</p>
 */
class DeltaIngestionStagedTtlConfigTest {

    /** {@code ${ENV_VAR:default}} — the shipped default is what runs when no env var is set. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    @Test
    void stagedSessionIsEvictedBeforeTheSweeperCanReapItsBatch() {
        Properties yaml = shippedDefaults();
        long stagedTtl = longDefault(yaml, "delta.ingestion.staged-ttl-millis");
        long sweepInterval = longDefault(yaml, "delta.ingestion.staged-sweep-millis");
        long batchTimeout = longDefault(yaml, "batch.timeout.minutes") * 60_000L;

        assertTrue(stagedTtl < batchTimeout,
                "staged-ttl-millis (" + stagedTtl + ") must be below the batch timeout ("
                        + batchTimeout + "), or a staged session stays resumable after the sweeper "
                        + "has already reaped its batch");
        assertTrue(stagedTtl + sweepInterval < batchTimeout,
                "even at worst-case sweep granularity (staged-ttl " + stagedTtl + " + sweep "
                        + sweepInterval + ") eviction must land inside the batch's life ("
                        + batchTimeout + ")");
    }

    @Test
    void serviceValueFallbackMatchesTheShippedYamlDefault() {
        long yamlDefault = longDefault(shippedDefaults(), "delta.ingestion.staged-ttl-millis");

        assertEquals(yamlDefault, annotationFallback("delta.ingestion.staged-ttl-millis"),
                "the @Value fallback in DeltaIngestionService must agree with application.yml, "
                        + "otherwise a stripped-down config silently restores the inverted TTL");
    }

    // ---------------------------------------------------------------- helpers

    private static Properties shippedDefaults() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties, "application.yml must be readable from the classpath");
        return properties;
    }

    private static long longDefault(Properties yaml, String key) {
        String raw = yaml.getProperty(key);
        assertNotNull(raw, "missing config key: " + key);
        Matcher matcher = PLACEHOLDER.matcher(raw.trim());
        return Long.parseLong(matcher.matches() ? matcher.group(1) : raw.trim());
    }

    /** The {@code @Value("${key:default}")} fallback declared on the service constructor. */
    private static long annotationFallback(String key) {
        for (Constructor<?> constructor : DeltaIngestionService.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null && value.value().contains(key)) {
                    Matcher matcher = PLACEHOLDER.matcher(value.value().trim());
                    assertTrue(matcher.matches(), "expected a ${key:default} placeholder for " + key);
                    return Long.parseLong(matcher.group(1));
                }
            }
        }
        throw new AssertionError("no @Value constructor parameter for " + key);
    }
}
