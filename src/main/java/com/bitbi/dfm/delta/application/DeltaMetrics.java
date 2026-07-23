package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Micrometer meters for Delta Client v2 ingestion (022, T5.3). Exposed via {@code /actuator/metrics}.
 *
 * <ul>
 *   <li>{@code delta.sessions.started} / {@code delta.sessions.committed} — session lifecycle counters</li>
 *   <li>{@code delta.reconciliation.failures} — sessions rejected at SessionEnd (CR §10)</li>
 *   <li>{@code delta.sessions.overflow} — sessions rejected for exceeding the per-session buffer
 *       cap (the OOM guard), tagged {@code reason=records|bytes} by which cap tripped</li>
 *   <li>{@code delta.checkpoint.duration} — time to materialize a checkpoint</li>
 *   <li>{@code delta.seq.lag} — committed seq beyond the last checkpoint at commit (changelog backlog)</li>
 *   <li>{@code delta.egress.segments} — segments materialized as delta Parquet (Task 8)</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaMetrics {

    private static final String APP_TAG_KEY = "application";
    private static final String APP_TAG_VALUE = "data-forge-middleware";

    private final Counter sessionsStarted;
    private final Counter sessionsCommitted;
    private final Counter reconciliationFailures;
    private final Counter sessionOverflowsRecords;
    private final Counter sessionOverflowsBytes;
    private final Timer checkpointDuration;
    private final DistributionSummary seqLag;
    private final Counter egressSegments;

    public DeltaMetrics(MeterRegistry registry) {
        this.sessionsStarted = Counter.builder("delta.sessions.started")
                .description("Delta ingestion sessions opened")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.sessionsCommitted = Counter.builder("delta.sessions.committed")
                .description("Delta ingestion sessions committed")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.reconciliationFailures = Counter.builder("delta.reconciliation.failures")
                .description("Delta sessions rejected for failing reconciliation at SessionEnd")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.sessionOverflowsRecords = Counter.builder("delta.sessions.overflow")
                .description("Delta sessions rejected for exceeding the per-session buffer cap")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag("reason", "records").register(registry);
        this.sessionOverflowsBytes = Counter.builder("delta.sessions.overflow")
                .description("Delta sessions rejected for exceeding the per-session buffer cap")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag("reason", "bytes").register(registry);
        this.checkpointDuration = Timer.builder("delta.checkpoint.duration")
                .description("Time to materialize a delta checkpoint")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.seqLag = DistributionSummary.builder("delta.seq.lag")
                .description("Committed seq beyond the last checkpoint at session commit")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.egressSegments = Counter.builder("delta.egress.segments")
                .description("Changelog segments materialized as delta Parquet egress")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
    }

    /** A new ingestion session opened a batch. */
    public void sessionStarted() {
        sessionsStarted.increment();
    }

    /** A session (or a resumed session) committed its segment. */
    public void sessionCommitted() {
        sessionsCommitted.increment();
    }

    /** A session was rejected at SessionEnd for failing reconciliation. */
    public void reconciliationFailed() {
        reconciliationFailures.increment();
    }

    /** A session was rejected for exceeding the per-session record cap. */
    public void sessionOverflowedRecords() {
        sessionOverflowsRecords.increment();
    }

    /** A session was rejected for exceeding the per-session byte budget. */
    public void sessionOverflowedBytes() {
        sessionOverflowsBytes.increment();
    }

    /** Record how far the committed watermark is ahead of the last checkpoint. Negative is ignored. */
    public void recordSeqLag(long lag) {
        if (lag >= 0) {
            seqLag.record(lag);
        }
    }

    /** Time a checkpoint build, returning the supplier's value. */
    public <T> T timeCheckpoint(Supplier<T> build) {
        return checkpointDuration.record(build);
    }

    /** A changelog segment's delta Parquet egress was materialized. */
    public void segmentEgressed() {
        egressSegments.increment();
    }
}
