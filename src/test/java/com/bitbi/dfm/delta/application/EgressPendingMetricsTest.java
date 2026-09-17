package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgressPendingMetricsTest {

    /**
     * The bound binder, held past the last gauge read (#316).
     *
     * <p>{@code Gauge.builder(name, target, fn)} keeps the target weakly, so a binder this class
     * did not reference would be collected and every read below would answer {@code NaN}. A field
     * rather than a local: the test instance is reachable from the running frame for the whole
     * method, while a local the method never reads again may be collected where it stands. See
     * {@code MeterBinderReachabilityConventionTest}.</p>
     */
    @SuppressWarnings("unused")
    private EgressPendingMetrics boundMetrics;

    @Test
    void exportsPendingEgressCountAndRefreshesAfterItsTtl() {
        ChangelogSegmentRepository repository = mock(ChangelogSegmentRepository.class);
        AtomicLong clock = new AtomicLong(-Duration.ofSeconds(100).toNanos());
        when(repository.countPendingEgress())
                .thenAnswer(ignored -> {
                    clock.addAndGet(Duration.ofSeconds(6).toNanos());
                    return 3L;
                })
                .thenReturn(11L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        boundMetrics = new EgressPendingMetrics(repository, clock::get, Duration.ofSeconds(5));
        boundMetrics.bindTo(registry);

        assertEquals(3.0, registry.get("delta.egress.pending").gauge().value());
        verify(repository, times(1)).countPendingEgress();

        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertEquals(11.0, registry.get("delta.egress.pending").gauge().value(),
                "the pending count must refresh after its bounded reuse window");
        verify(repository, times(2)).countPendingEgress();
    }
}
