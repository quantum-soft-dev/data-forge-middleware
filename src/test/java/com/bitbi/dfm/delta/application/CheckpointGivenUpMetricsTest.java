package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.CheckpointRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #149 — the standing signal that replaces the nightly alarm. Retiring a checkpoint row that
 * no build can materialize removes the noise; without this gauge it would remove the signal too,
 * leaving a table permanently missing from Bit BI and Parquet Export with nothing left to say so.
 */
class CheckpointGivenUpMetricsTest {

    private static final int MAX_ATTEMPTS = 5;

    private final CheckpointRepository repository = mock(CheckpointRepository.class);
    private final CheckpointRetryProperties retryProperties = new CheckpointRetryProperties(MAX_ATTEMPTS);
    private final AtomicLong clock = new AtomicLong();

    /**
     * The bound binder, held past the last gauge read (#316).
     *
     * <p>{@code Gauge.builder(name, target, fn)} keeps the target weakly, so the binder
     * {@link #bind(Duration, SimpleMeterRegistry)} builds would otherwise be collected and every
     * read below would answer {@code NaN}. A field rather than a local: the test instance is
     * reachable from the running frame for the whole method, while a local the method never reads
     * again may be collected where it stands. See {@code MeterBinderReachabilityConventionTest}.</p>
     */
    @SuppressWarnings("unused")
    private CheckpointGivenUpMetrics boundMetrics;

    @Test
    void gaugesTheRowsTheNightlyPassHasStoppedRetrying() {
        when(repository.countGivenUpMaterializing(MAX_ATTEMPTS)).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        bind(Duration.ofSeconds(30), registry);

        assertEquals(3.0, gauge(registry).value());
    }

    @Test
    void asksTheRepositoryWithTheConfiguredCeiling() {
        // The ceiling has to be the same number the service skips on and the scheduler filters by,
        // or the gauge would count a population nobody has actually given up on.
        when(repository.countGivenUpMaterializing(anyInt())).thenReturn(0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        bind(Duration.ofSeconds(30), registry);
        gauge(registry).value();

        verify(repository).countGivenUpMaterializing(MAX_ATTEMPTS);
    }

    @Test
    void doesNotQueryOncePerScrapeWithinTheSnapshotWindow() {
        // The number changes at most once a night; a scrape must not turn into a table scan.
        when(repository.countGivenUpMaterializing(MAX_ATTEMPTS)).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        bind(Duration.ofSeconds(30), registry);

        gauge(registry).value();
        gauge(registry).value();
        gauge(registry).value();

        verify(repository, times(1)).countGivenUpMaterializing(MAX_ATTEMPTS);
    }

    @Test
    void refreshesOnceTheSnapshotHasExpired() {
        when(repository.countGivenUpMaterializing(MAX_ATTEMPTS)).thenReturn(1L, 4L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        bind(Duration.ofSeconds(30), registry);

        assertEquals(1.0, gauge(registry).value());
        clock.addAndGet(Duration.ofSeconds(31).toNanos());

        assertEquals(4.0, gauge(registry).value());
    }

    /** Builds the binder, keeps it in {@link #boundMetrics} and binds it to {@code registry}. */
    private void bind(Duration ttl, SimpleMeterRegistry registry) {
        boundMetrics = new CheckpointGivenUpMetrics(repository, retryProperties, clock::get, ttl);
        boundMetrics.bindTo(registry);
    }

    private static Gauge gauge(SimpleMeterRegistry registry) {
        return registry.get("delta.checkpoint.tables.given-up").gauge();
    }
}
