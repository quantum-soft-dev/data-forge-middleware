package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #404, review round 1: a segment that keeps <em>killing</em> its process never reaches the
 * deferral that moves {@code *.segments.poisoned}, so those counters cannot see the incident class
 * #404 is about. The census can: it counts what is still pending past the threshold, however its
 * attempts were spent.
 */
class PoisonedSegmentMetricsTest {

    /** Held past the last gauge read, since a gauge keeps its target weakly (#316). */
    @SuppressWarnings("unused")
    private PoisonedSegmentMetrics boundMetrics;

    private ChangelogSegmentRepository repository;
    private SimpleMeterRegistry registry;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        repository = mock(ChangelogSegmentRepository.class);
        registry = new SimpleMeterRegistry();
        clock = new AtomicLong(-Duration.ofSeconds(100).toNanos());
    }

    @Test
    void countsPendingSegmentsAtOrPastEachQueuesOwnThreshold() {
        when(repository.countPendingEgressAttemptedAtLeast(7)).thenReturn(2L);
        when(repository.countPendingPluginSqlAttemptedAtLeast(5)).thenReturn(4L);

        boundMetrics = new PoisonedSegmentMetrics(repository, 7, 5, clock::get, Duration.ofSeconds(5));
        boundMetrics.bindTo(registry);

        assertEquals(2.0, registry.get("delta.egress.segments.poisoned.pending").gauge().value());
        assertEquals(4.0, registry.get("sql.generation.delta.segments.poisoned.pending").gauge().value());
    }

    @Test
    void reusesASnapshotWithinItsTtlAndRefreshesAfterIt() {
        when(repository.countPendingEgressAttemptedAtLeast(7)).thenReturn(1L, 3L);

        boundMetrics = new PoisonedSegmentMetrics(repository, 7, 7, clock::get, Duration.ofSeconds(5));
        boundMetrics.bindTo(registry);

        assertEquals(1.0, registry.get("delta.egress.segments.poisoned.pending").gauge().value());
        assertEquals(1.0, registry.get("delta.egress.segments.poisoned.pending").gauge().value());
        verify(repository, times(1)).countPendingEgressAttemptedAtLeast(7);

        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertEquals(3.0, registry.get("delta.egress.segments.poisoned.pending").gauge().value());
        verify(repository, times(2)).countPendingEgressAttemptedAtLeast(7);
    }

    @Test
    void refusesAThresholdBelowOneNamingTheKey() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new PoisonedSegmentMetrics(repository, 0, 7));
        assertEquals(true, refused.getMessage().contains("delta.egress.poison-after-attempts"),
                refused.getMessage());
    }
}
