package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #251 — {@code @Scheduled(fixedDelayString)} accepts {@code 0} and busy-loops, and a
 * negative value fails Spring's parser without naming the key. The validator walks every
 * interval placeholder, so a newly added key cannot ship unvalidated, and the refusal names
 * the key and the value so the crash-loop line says what to fix.
 */
@DisplayName("Scheduled interval validator (#251)")
class ScheduledIntervalValidatorTest {

    /**
     * The keys the ticket named plus the one #185 already closed. The scanner must find all of
     * them; the set itself is not closed — a newcomer is validated automatically, and this list
     * is the mutation pin that a scan gone blind cannot satisfy with an empty map.
     */
    private static final Set<String> TICKET_INTERVAL_KEYS = Set.of(
            "delta.batch-parquet.sweep-ms",
            "delta.egress.sweep-ms",
            "delta.s3-orphan.sweep-ms",
            "delta.parquet.scratch-orphan-sweep-ms",
            "delta.ingestion.staged-sweep-millis",
            "delta.ingestion.provisional-sweep-millis",
            "plugin.parquet-export.purge-interval-ms",
            "plugin.sql-generation.delta-sweep-ms");

    @Test
    @DisplayName("discovers every interval key the ticket named, and no initial-delay key")
    void shouldDiscoverTheTicketIntervalKeysAndIgnoreInitialDelay() {
        NavigableMap<String, String> discovered = ScheduledIntervalValidator.discoverIntervalPlaceholders();

        assertFalse(discovered.isEmpty(),
                "no interval placeholder was found — the production scan is broken");
        assertTrue(discovered.keySet().containsAll(TICKET_INTERVAL_KEYS),
                "production scan missed an interval key the ticket named: expected "
                        + TICKET_INTERVAL_KEYS + " to be a subset of " + discovered.keySet());
        assertFalse(discovered.containsKey("delta.s3-orphan.initial-delay-ms"),
                "initialDelayString is fire-immediately at 0 and must not be treated as an interval");
        for (String expression : discovered.values()) {
            assertTrue(expression.contains("${"),
                    "discoverIntervalPlaceholders must keep the placeholder expression so "
                            + "the annotation default is used when the key is unset: " + expression);
        }
    }

    @Test
    @DisplayName("shipped application.yml values are all at least 1 ms")
    void shouldAcceptTheShippedDefaults() {
        assertDoesNotThrow(() -> new ScheduledIntervalValidator(IsolatedEnvironments.loadConfig()),
                "the shipped interval keys must pass the floor — a default of 0 would busy-loop");
    }

    @Test
    @DisplayName("test-profile overrides stay above the floor")
    void shouldAcceptTheTestProfileOverrides() {
        assertDoesNotThrow(() -> new ScheduledIntervalValidator(IsolatedEnvironments.loadConfig("test")),
                "application-test.yml slows several interval keys; those overrides must stay >= 1");
    }

    @ParameterizedTest(name = "should refuse a resolved interval of {0}, naming the key and the value")
    @ValueSource(strings = {"0", "-1", "-5", "PT0S"})
    void shouldRefuseNonPositiveIntervalNamingTheKeyAndTheValue(String raw) {
        NavigableMap<String, String> discovered = ScheduledIntervalValidator.discoverIntervalPlaceholders();
        assertFalse(discovered.isEmpty(), "scan is broken");

        String expectedMillis = "PT0S".equals(raw) ? "0" : raw;
        for (String key : discovered.keySet()) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new ScheduledIntervalValidator(
                            IsolatedEnvironments.loadConfig(Map.of(key, raw))),
                    () -> key + " = " + raw + " must fail startup, otherwise 0 still busy-loops");
            assertTrue(thrown.getMessage().contains(key),
                    "refusal must name the configuration key, got: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("but was " + expectedMillis),
                    "refusal must name the offending value, got: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("should refuse an unparseable interval, naming the key and the raw value")
    void shouldRefuseUnparseableIntervalNamingTheKeyAndTheRawValue() {
        String key = "delta.egress.sweep-ms";
        String raw = "nope";

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new ScheduledIntervalValidator(
                        IsolatedEnvironments.loadConfig(Map.of(key, raw))));
        assertTrue(thrown.getMessage().contains(key),
                "unparseable refusal must name the key, got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(raw),
                "unparseable refusal must quote the raw value, got: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a one-millisecond interval is the floor, not a refusal")
    void shouldAcceptOneMillisecondFloor() {
        String key = "delta.egress.sweep-ms";
        StandardEnvironment environment = IsolatedEnvironments.loadConfig(Map.of(key, "1"));

        assertDoesNotThrow(() -> new ScheduledIntervalValidator(environment));
    }

    @Test
    @DisplayName("the independent inventory scan and the production scan see the same keys")
    void shouldAgreeWithTheInventoryScan() {
        Set<String> inventory = ScheduledTaskInventoryTest.scanScheduledIntervalKeys();
        Set<String> production = new TreeSet<>(
                ScheduledIntervalValidator.discoverIntervalPlaceholders().keySet());

        assertEquals(inventory, production,
                "two scans of the same @Scheduled interval keys must agree; disagreement is a "
                        + "scan gone blind, not a clean application");
    }
}
