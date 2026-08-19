package com.bitbi.dfm.integration;

import com.bitbi.dfm.config.TestJvmHeapCeilingTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #207 — the {@code integrationTest} JVM runs on the ceiling the build declares.
 *
 * <p>{@link TestJvmHeapCeilingTest} reads the build script and then checks the JVM it is itself
 * running in. That class lives under {@code config/}, and {@code integrationTest} is
 * {@code include("**}{@code /integration/**")}, so it never observes this task at all: a
 * {@code -Xmx} added to {@code integrationTest}'s own {@code jvmArgs} would leave every assertion
 * there green while the Testcontainers suite — the task that boots the most Spring contexts, and
 * therefore the one with the most to lose — ran on 512 MB. Found in review of #207.</p>
 *
 * <p>Deliberately <b>not</b> a Spring test: it starts no context and no container, it only reads
 * {@code Runtime.maxMemory()} of the JVM it was given. It lives in this package for one reason —
 * that is what puts it inside the {@code integrationTest} filter. It runs under {@code test} too,
 * where it duplicates its twin harmlessly and costs nothing.</p>
 */
@DisplayName("Test JVM heap ceiling under integrationTest (#207)")
class TestJvmHeapCeilingIntegrationTest {

    @Test
    @DisplayName("the integrationTest JVM was given the declared ceiling")
    void shouldRunWithTheDeclaredCeiling() {
        TestJvmHeapCeilingTest.assertRunsWithTheDeclaredCeiling();
    }
}
