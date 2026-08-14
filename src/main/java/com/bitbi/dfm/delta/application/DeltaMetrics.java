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
 *   <li>{@code delta.batch-parquet.artifacts} — completed-batch artifacts settled, tagged
 *       {@code outcome=ready|failed|abandoned}; {@code abandoned} is a permanently 404-ing
 *       user-facing download and is the one worth alerting on (036)</li>
 *   <li>{@code delta.batch-parquet.duration} — time to replay and upload one claimed batch group</li>
 *   <li>{@code delta.batch-parquet.reclaims} — claims taken over after their build lease expired;
 *       a rising count means builds are outrunning {@code lease-seconds}</li>
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
    private final Counter checkpointNoSchema;
    private final Counter checkpointParquetFailed;
    private final Counter batchParquetReady;
    private final Counter batchParquetFailed;
    private final Counter batchParquetAbandoned;
    private final Counter batchParquetReclaims;
    private final Timer batchParquetDuration;

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
        this.checkpointNoSchema = checkpointUnmaterialized(registry, "no_schema");
        this.checkpointParquetFailed = checkpointUnmaterialized(registry, "parquet_failed");
        this.batchParquetReady = batchParquetOutcome(registry, "ready");
        this.batchParquetFailed = batchParquetOutcome(registry, "failed");
        this.batchParquetAbandoned = batchParquetOutcome(registry, "abandoned");
        this.batchParquetReclaims = Counter.builder("delta.batch-parquet.reclaims")
                .description("Completed-batch Parquet claims taken over after their lease expired")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.batchParquetDuration = Timer.builder("delta.batch-parquet.duration")
                .description("Time to replay and upload one completed-batch Parquet claim group")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
    }

    private static Counter checkpointUnmaterialized(MeterRegistry registry, String reason) {
        return Counter.builder("delta.checkpoint.tables.unmaterialized")
                .description("Checkpoint tables that produced no downloadable artifact, by reason")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag("reason", reason).register(registry);
    }

    private static Counter batchParquetOutcome(MeterRegistry registry, String outcome) {
        return Counter.builder("delta.batch-parquet.artifacts")
                .description("Completed-batch Parquet artifacts settled, by outcome")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag("outcome", outcome).register(registry);
    }

    /**
     * One table came out of a checkpoint build with no downloadable artifact.
     *
     * <p>Since issue #113 Parquet is the only format the build writes, so a table without a
     * declared schema ({@code no_schema}) or one whose Parquet write threw ({@code parquet_failed})
     * has nothing to download until the next build. Both used to be masked by the CSV snapshot.</p>
     *
     * @param reason {@code no_schema} or {@code parquet_failed}
     */
    public void checkpointTableUnmaterialized(String reason) {
        switch (reason) {
            case "no_schema" -> checkpointNoSchema.increment();
            case "parquet_failed" -> checkpointParquetFailed.increment();
            default -> throw new IllegalArgumentException("Unknown reason: " + reason);
        }
    }

    /** One table's completed-batch artifact is published and downloadable. */
    public void batchParquetReady() {
        batchParquetReady.increment();
    }

    /** An attempt failed but the artifact stays retryable. */
    public void batchParquetFailed() {
        batchParquetFailed.increment();
    }

    /** An artifact used up its attempts: its download answers 404 until someone intervenes. */
    public void batchParquetAbandoned() {
        batchParquetAbandoned.increment();
    }

    /** A claim was taken over because its build lease had expired. */
    public void batchParquetReclaimed() {
        batchParquetReclaims.increment();
    }

    /** Time one batch-level claim group (shared replay + uploads), whatever its outcomes. */
    public <T> T timeBatchParquetBuild(Supplier<T> build) {
        return batchParquetDuration.record(build);
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
