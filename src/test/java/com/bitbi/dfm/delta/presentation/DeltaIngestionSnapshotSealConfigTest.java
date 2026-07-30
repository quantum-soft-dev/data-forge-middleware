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
 * 033 review — a re-baseline must not seal on the CONTINUOUS record threshold.
 *
 * <p>100 records bounds staleness for a trickle stream. Applied to a bulk snapshot it cuts a 5M-row
 * dataset into 50,000 segments: 50,000 transactions, S3 objects and downstream queue items, each
 * seal stalling record intake for a full S3 round trip (inbound flow control only re-requests once
 * {@code onNext} returns). A snapshot is bounded by {@code continuous-seal-bytes} instead, with the
 * record threshold only capping very narrow rows — so the shipped default must stay well clear of
 * the continuous one.</p>
 */
class DeltaIngestionSnapshotSealConfigTest {

    /** {@code ${ENV_VAR:default}} — the shipped default is what runs when no env var is set. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    /** Mirrors {@code DeltaIngestionService.CONTINUOUS_SEAL_RECORDS}. */
    private static final long CONTINUOUS_SEAL_RECORDS = 100L;

    @Test
    void snapshotSealsFarLessOftenThanAContinuousStream() {
        long snapshotSeal = longDefault(shippedDefaults(), "delta.ingestion.snapshot-seal-records");

        assertTrue(snapshotSeal >= 100 * CONTINUOUS_SEAL_RECORDS,
                "snapshot-seal-records (" + snapshotSeal + ") must be orders of magnitude above the "
                        + "continuous threshold (" + CONTINUOUS_SEAL_RECORDS + "), or a large "
                        + "re-baseline produces tens of thousands of segments and stalls on each seal");
    }

    @Test
    void serviceValueFallbackMatchesTheShippedYamlDefault() {
        long yamlDefault = longDefault(shippedDefaults(), "delta.ingestion.snapshot-seal-records");

        assertEquals(yamlDefault, annotationFallback("delta.ingestion.snapshot-seal-records"),
                "the @Value fallback in DeltaIngestionService must agree with application.yml, or a "
                        + "stripped-down config silently changes the snapshot's segment shape");
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
                    assertTrue(matcher.matches(), "expected a ${key:default} placeholder");
                    return Long.parseLong(matcher.group(1));
                }
            }
        }
        throw new AssertionError("DeltaIngestionService has no @Value parameter for " + key);
    }
}
