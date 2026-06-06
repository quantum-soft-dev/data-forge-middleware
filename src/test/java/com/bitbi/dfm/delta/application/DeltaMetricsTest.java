package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T5.3 — {@link DeltaMetrics} registers the delta ingestion meters on the registry and increments
 * them: session counters, reconciliation failures, the checkpoint-duration timer, and the seq-lag
 * distribution.
 */
class DeltaMetricsTest {

    @Test
    void registersAndIncrementsMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        metrics.sessionStarted();
        metrics.sessionCommitted();
        metrics.sessionCommitted();
        metrics.reconciliationFailed();
        metrics.recordSeqLag(5L);
        metrics.recordSeqLag(3L);
        String built = metrics.timeCheckpoint(() -> "ok");

        assertEquals("ok", built, "the timed supplier's value is returned");
        assertEquals(1.0, registry.get("delta.sessions.started").counter().count());
        assertEquals(2.0, registry.get("delta.sessions.committed").counter().count());
        assertEquals(1.0, registry.get("delta.reconciliation.failures").counter().count());
        assertEquals(1L, registry.get("delta.checkpoint.duration").timer().count());

        DistributionSummary lag = registry.get("delta.seq.lag").summary();
        assertEquals(2L, lag.count());
        assertEquals(8.0, lag.totalAmount(), "5 + 3 lag recorded");
    }

    @Test
    void ignoresNegativeSeqLag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        metrics.recordSeqLag(-1L);

        assertEquals(0L, registry.get("delta.seq.lag").summary().count(), "negative lag is not recorded");
    }
}
