package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.3 / #111 — {@link DeltaMetrics} registers the delta ingestion meters on the registry
 * and increments them: session counters, reconciliation failures, the cycle-duration timers,
 * phase-tagged inner timers, and the seq-lag distribution.
 */
class DeltaMetricsTest {

    private static final List<String> BATCH_PARQUET_PHASES =
            List.of("download", "decode", "decimal_scan", "write", "upload", "total");
    private static final List<String> EGRESS_PHASES = List.of("download", "write", "upload", "total");
    private static final List<String> CHECKPOINT_PHASES =
            List.of("download_frame", "fold", "parquet", "upload", "total");

    @Test
    void registersAndIncrementsMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        metrics.sessionStarted();
        metrics.sessionCommitted();
        metrics.sessionCommitted();
        metrics.reconciliationFailed();
        metrics.sessionOverflowedRecords();
        metrics.sessionOverflowedBytes();
        metrics.sessionOverflowedBytes();
        metrics.recordSeqLag(5L);
        metrics.recordSeqLag(3L);
        String built = metrics.timeCheckpoint(() -> "ok");

        assertEquals("ok", built, "the timed supplier's value is returned");
        assertEquals(1.0, registry.get("delta.sessions.started").counter().count());
        assertEquals(2.0, registry.get("delta.sessions.committed").counter().count());
        assertEquals(1.0, registry.get("delta.reconciliation.failures").counter().count());
        // The reason tag tells an incident apart without going to logs: which cap tripped.
        assertEquals(1.0, registry.get("delta.sessions.overflow").tag("reason", "records").counter().count());
        assertEquals(2.0, registry.get("delta.sessions.overflow").tag("reason", "bytes").counter().count());
        assertEquals(1L, phaseTimer(registry, "delta.checkpoint.duration", "total").count());

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

    @Test
    void preRegistersEveryAllowlistedPhaseTimerAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DeltaMetrics(registry);

        for (String phase : BATCH_PARQUET_PHASES) {
            assertEquals(0L, phaseTimer(registry, "delta.batch-parquet.duration", phase).count(), phase);
        }
        for (String phase : EGRESS_PHASES) {
            assertEquals(0L, phaseTimer(registry, "delta.egress.duration", phase).count(), phase);
        }
        for (String phase : CHECKPOINT_PHASES) {
            assertEquals(0L, phaseTimer(registry, "delta.checkpoint.duration", phase).count(), phase);
        }
        assertEquals(0L, phaseTimer(registry, "delta.egress.duration", "total").count());
        assertEquals(0L, phaseTimer(registry, "delta.batch-parquet.duration", "total").count());
    }

    @Test
    void recordsPhaseSamplesOnTheSameMeterName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        metrics.recordBatchParquetPhase("download", TimeUnit.MILLISECONDS.toNanos(12));
        metrics.recordBatchParquetPhase("write", TimeUnit.MILLISECONDS.toNanos(40));
        metrics.recordEgressPhase("upload", TimeUnit.MILLISECONDS.toNanos(7));
        String folded = metrics.timeCheckpointPhase("fold", () -> "folded");
        String egressed = metrics.timeEgress(() -> "done");

        assertEquals("folded", folded);
        assertEquals("done", egressed);
        assertEquals(1L, phaseTimer(registry, "delta.batch-parquet.duration", "download").count());
        assertTrue(phaseTimer(registry, "delta.batch-parquet.duration", "download")
                .totalTime(TimeUnit.MILLISECONDS) >= 12.0);
        assertEquals(1L, phaseTimer(registry, "delta.batch-parquet.duration", "write").count());
        assertEquals(0L, phaseTimer(registry, "delta.batch-parquet.duration", "decode").count());
        assertEquals(1L, phaseTimer(registry, "delta.egress.duration", "upload").count());
        assertEquals(1L, phaseTimer(registry, "delta.checkpoint.duration", "fold").count());
        assertEquals(1L, phaseTimer(registry, "delta.egress.duration", "total").count());
    }

    @Test
    void timesARunnablePhaseWithoutADummyReturn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();

        metrics.timeEgress(() -> ran.set(true));
        metrics.timeCheckpointPhase("upload", () -> { });

        assertTrue(ran.get());
        assertEquals(1L, phaseTimer(registry, "delta.egress.duration", "total").count());
        assertEquals(1L, phaseTimer(registry, "delta.checkpoint.duration", "upload").count());
    }

    @Test
    void countsAnAbortedCheckpointBuildByReason() {
        // Issue #153. Every other way a checkpoint build can end badly has a counter; a frame that
        // crosses its ceiling had only an ERROR line, so the site's frozen pointer — and the
        // retention that stops with it — was invisible until someone read the logs or noticed the
        // segment table growing.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        assertEquals(0.0, abortCounter(registry, "frame_too_large").count(),
                "the series must exist before the first abort, or an alert has nothing to watch");
        assertEquals(0.0, abortCounter(registry, "lossy_refold").count());
        assertEquals(0.0, abortCounter(registry, "history_gone").count());

        metrics.checkpointBuildAborted("frame_too_large");
        metrics.checkpointBuildAborted("frame_too_large");
        metrics.checkpointBuildAborted("lossy_refold");
        metrics.checkpointBuildAborted("history_gone");

        assertEquals(2.0, abortCounter(registry, "frame_too_large").count());
        // The other abort that freezes the pointer permanently. Both must be on the meter, or the
        // alert the guide tells operators to write silently misses half the population.
        assertEquals(1.0, abortCounter(registry, "lossy_refold").count());
        // And the third (issue #149): a frame that is gone with no segments behind it is not a
        // lossy refold — there is no history to refold — and the recovery is a different one.
        assertEquals(1.0, abortCounter(registry, "history_gone").count());
    }

    @Test
    void countsADeferredBuildApartFromTheAbortsThatDoNotRepairThemselves() {
        // Issue #178. A build deferred behind the process's fold budget concluded nothing about the
        // site and clears as soon as the neighbouring build finishes, so it must not land on
        // delta.checkpoint.builds.aborted — an alert written on that meter (which is what #153
        // built it for) would page for a condition that fixes itself.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        assertEquals(0.0, registry.get("delta.checkpoint.builds.deferred").counter().count(),
                "the series must exist before the first deferral, or an alert has nothing to watch");

        metrics.checkpointBuildDeferred();
        metrics.checkpointBuildDeferred();

        assertEquals(2.0, registry.get("delta.checkpoint.builds.deferred").counter().count());
        assertEquals(0.0, abortCounter(registry, "fold_too_large").count(),
                "a deferral is not a fold that outgrew the heap");
        assertEquals(0.0, abortCounter(registry, "frame_too_large").count());
        assertEquals(0.0, abortCounter(registry, "lossy_refold").count());
        assertEquals(0.0, abortCounter(registry, "history_gone").count());
    }

    @Test
    void rejectsAnUnknownCheckpointAbortReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        assertThrows(IllegalArgumentException.class,
                () -> metrics.checkpointBuildAborted("frame_to_large"));
    }

    @Test
    void rejectsUnknownPhaseTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        assertThrows(IllegalArgumentException.class,
                () -> metrics.recordBatchParquetPhase("encode", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.recordEgressPhase("decode", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.recordCheckpointPhase("write", 1L));
    }

    @Test
    void ignoresNegativePhaseNanos() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeltaMetrics metrics = new DeltaMetrics(registry);

        metrics.recordBatchParquetPhase("download", -1L);

        assertEquals(0L, phaseTimer(registry, "delta.batch-parquet.duration", "download").count());
    }

    private static io.micrometer.core.instrument.Counter abortCounter(SimpleMeterRegistry registry,
                                                                      String reason) {
        return registry.get("delta.checkpoint.builds.aborted").tag("reason", reason).counter();
    }

    private static Timer phaseTimer(SimpleMeterRegistry registry, String name, String phase) {
        Meter meter = registry.get(name).tag("phase", phase).meter();
        return (Timer) meter;
    }
}
