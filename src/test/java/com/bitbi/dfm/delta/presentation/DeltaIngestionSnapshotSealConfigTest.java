package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaRebaselineService;
import com.bitbi.dfm.delta.application.DeltaSessionCommitService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.site.application.SiteSchemaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    @Test
    void shippedDefaultsKeepTheSnapshotSealBelowTheSessionRecordCap() {
        Properties yaml = shippedDefaults();

        assertTrue(longDefault(yaml, "delta.ingestion.snapshot-seal-records")
                        < longDefault(yaml, "delta.ingestion.max-session-records"),
                "a snapshot must seal before it can overflow, or a re-baseline is unrecoverable");
    }

    @Test
    void aSnapshotSealAtOrAboveTheRecordCapIsRefusedAtStartup() {
        // dbf-data-extractor#130: OVERFLOW tells a DELTA session to retry in CONTINUOUS mode, but a
        // FULL_SNAPSHOT has nowhere to go — a CONTINUOUS snapshot would lose the snapshot semantics
        // the mode exists for. So a snapshot that can reach the cap before its seal fires is not a
        // recoverable session error, it is a dead site, and the only place to catch it is boot.
        IllegalArgumentException equal = assertThrows(IllegalArgumentException.class,
                () -> serviceWith(25_000, 25_000));
        assertTrue(equal.getMessage().contains("snapshot-seal-records"),
                "the failure must name the offending key: " + equal.getMessage());
        assertTrue(equal.getMessage().contains("max-session-records"),
                "and the one it must stay below: " + equal.getMessage());

        assertThrows(IllegalArgumentException.class, () -> serviceWith(25_001, 25_000),
                "a seal above the cap is the same unrecoverable shape");
    }

    @Test
    void aSnapshotSealBelowTheRecordCapStarts() {
        assertDoesNotThrow(() -> serviceWith(24_999, 25_000));
        assertDoesNotThrow(() -> serviceWith(25_000, 2_000_000)); // the shipped pair
    }

    /** The service with everything mocked but the two record settings under test. */
    private static DeltaIngestionService serviceWith(int snapshotSealRecords, int maxSessionRecords) {
        return new DeltaIngestionService(
                mock(DeltaSyncStateService.class),
                mock(BatchLifecycleService.class),
                mock(SiteSchemaService.class),
                mock(DeltaSessionCommitService.class),
                mock(DeltaRebaselineService.class),
                mock(DeltaMetrics.class),
                maxSessionRecords,
                64L * 1024 * 1024,  // explicit budget: the auto one varies with the test JVM's heap
                3_000_000L,
                300_000L,
                16L * 1024 * 1024,
                500,
                snapshotSealRecords);
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
