package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer meters for Delta Client v2 ingestion (022, T5.3). Exposed via {@code /actuator/metrics}.
 *
 * <ul>
 *   <li>{@code delta.sessions.started} / {@code delta.sessions.committed} — session lifecycle counters</li>
 *   <li>{@code delta.reconciliation.failures} — sessions rejected at SessionEnd (CR §10)</li>
 *   <li>{@code delta.sessions.overflow} — sessions rejected for exceeding the per-session buffer
 *       cap (the OOM guard), tagged {@code reason=records|bytes} by which cap tripped</li>
 *   <li>{@code delta.checkpoint.duration} — time to materialize a checkpoint;
 *       {@code phase=total} is the cycle, {@code download_frame|fold|parquet|upload} are
 *       the inner steps (042)</li>
 *   <li>{@code delta.checkpoint.builds.aborted} — checkpoint builds abandoned whole, tagged
 *       {@code reason=frame_too_large|lossy_refold}; the pointer does not move, so retention
 *       freezes with it, and neither cause repairs itself</li>
 *   <li>{@code delta.seq.lag} — committed seq beyond the last checkpoint at commit (changelog backlog)</li>
 *   <li>{@code delta.egress.segments} — segments materialized as delta Parquet (Task 8)</li>
 *   <li>{@code delta.egress.duration} — per-segment egress; {@code phase=total} plus
 *       {@code download|write|upload}</li>
 *   <li>{@code delta.batch-parquet.artifacts} — completed-batch artifacts settled, tagged
 *       {@code outcome=ready|failed|abandoned}; {@code abandoned} is a permanently 404-ing
 *       user-facing download and is the one worth alerting on (036)</li>
 *   <li>{@code delta.batch-parquet.duration} — time to replay and upload one claimed batch group;
 *       {@code phase=total} is the cycle, {@code download|decode|decimal_scan|write|upload}
 *       are the inner steps (042)</li>
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
    private static final String PHASE_TAG = "phase";
    static final String PHASE_TOTAL = "total";
    static final Set<String> BATCH_PARQUET_PHASES =
            Set.of("download", "decode", "decimal_scan", "write", "upload", PHASE_TOTAL);
    static final Set<String> EGRESS_PHASES = Set.of("download", "write", "upload", PHASE_TOTAL);
    static final Set<String> CHECKPOINT_PHASES =
            Set.of("download_frame", "fold", "parquet", "upload", PHASE_TOTAL);

    private final Counter sessionsStarted;
    private final Counter sessionsCommitted;
    private final Counter reconciliationFailures;
    private final Counter sessionOverflowsRecords;
    private final Counter sessionOverflowsBytes;
    private final DistributionSummary seqLag;
    private final Counter egressSegments;
    private final Counter checkpointNoSchema;
    private final Counter checkpointParquetFailed;
    private final Counter checkpointFrameTooLarge;
    private final Counter checkpointLossyRefold;
    private final Counter batchParquetReady;
    private final Counter batchParquetFailed;
    private final Counter batchParquetAbandoned;
    private final Counter batchParquetReclaims;
    private final Map<String, Timer> batchParquetPhases;
    private final Map<String, Timer> egressPhases;
    private final Map<String, Timer> checkpointPhases;

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
        this.seqLag = DistributionSummary.builder("delta.seq.lag")
                .description("Committed seq beyond the last checkpoint at session commit")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.egressSegments = Counter.builder("delta.egress.segments")
                .description("Changelog segments materialized as delta Parquet egress")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.checkpointNoSchema = checkpointUnmaterialized(registry, "no_schema");
        this.checkpointParquetFailed = checkpointUnmaterialized(registry, "parquet_failed");
        this.checkpointFrameTooLarge = checkpointBuildAborted(registry, "frame_too_large");
        this.checkpointLossyRefold = checkpointBuildAborted(registry, "lossy_refold");
        this.batchParquetReady = batchParquetOutcome(registry, "ready");
        this.batchParquetFailed = batchParquetOutcome(registry, "failed");
        this.batchParquetAbandoned = batchParquetOutcome(registry, "abandoned");
        this.batchParquetReclaims = Counter.builder("delta.batch-parquet.reclaims")
                .description("Completed-batch Parquet claims taken over after their lease expired")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.batchParquetPhases = phaseTimers(registry, "delta.batch-parquet.duration",
                "Time spent in one completed-batch Parquet phase", BATCH_PARQUET_PHASES);
        this.egressPhases = phaseTimers(registry, "delta.egress.duration",
                "Time spent in one per-segment egress phase", EGRESS_PHASES);
        this.checkpointPhases = phaseTimers(registry, "delta.checkpoint.duration",
                "Time spent in one checkpoint materialization phase", CHECKPOINT_PHASES);
    }

    private static Map<String, Timer> phaseTimers(MeterRegistry registry, String name,
                                                  String description, Set<String> phases) {
        Map<String, Timer> timers = new LinkedHashMap<>();
        for (String phase : phases) {
            timers.put(phase, Timer.builder(name)
                    .description(description)
                    .tag(APP_TAG_KEY, APP_TAG_VALUE)
                    .tag(PHASE_TAG, phase)
                    .register(registry));
        }
        return Map.copyOf(timers);
    }

    private static Counter checkpointUnmaterialized(MeterRegistry registry, String reason) {
        return Counter.builder("delta.checkpoint.tables.unmaterialized")
                .description("Checkpoint tables that produced no downloadable artifact, by reason")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag("reason", reason).register(registry);
    }

    private static Counter checkpointBuildAborted(MeterRegistry registry, String reason) {
        return Counter.builder("delta.checkpoint.builds.aborted")
                .description("Checkpoint builds abandoned whole, leaving the pointer where it was, by reason")
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

    /**
     * A whole checkpoint build was abandoned (issue #153).
     *
     * <p>Unlike {@link #checkpointTableUnmaterialized(String)} this is not a hole in one table:
     * {@code last_checkpoint_seq} does not move, so {@code ChangelogRetentionService} prunes
     * nothing and the site's segment table grows until the cause is fixed. {@code delta.seq.lag}
     * is the companion series showing how far behind the site has fallen while it lasted.</p>
     *
     * <p>The tag values are the aborts that <b>do not repair themselves</b>, which is what makes a
     * non-zero rate a page rather than a blip: {@code frame_too_large} is deterministic for a given
     * fold, and {@code lossy_refold} is a seed frame that reads as absent over a pruned history.
     * Three other ways a build can end are deliberately absent — an unreadable scratch directory
     * and an S3 refusal on the frame are transient and cost only that tick (the first would also
     * hit every site at once, so it is an infrastructure alarm rather than a site's), and a build
     * discarded because the site's history was replaced under it (issues #136, #142) is a normal
     * outcome of an operator action, not a frozen pointer.</p>
     *
     * <p><b>One caveat on {@code lossy_refold}, and it is the reason to read the count per site
     * before acting:</b> {@code S3CheckpointStorage.exists} deliberately treats a {@code 403} as
     * absence, because least-privilege IAM answers HEAD-on-a-missing-key that way. A bucket-policy
     * or IAM read outage therefore makes the frame read as absent for <em>every</em> site at once,
     * and each pruned-history site increments this counter for a condition that a permission fix
     * clears. Many sites tripping in the same tick is that; one site tripping alone is the real
     * thing. The 403 branch also logs a WARN naming the key, which is the tiebreaker.</p>
     *
     * @param reason {@code frame_too_large} or {@code lossy_refold}
     */
    public void checkpointBuildAborted(String reason) {
        switch (reason) {
            case "frame_too_large" -> checkpointFrameTooLarge.increment();
            case "lossy_refold" -> checkpointLossyRefold.increment();
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
        return timeBatchParquetPhase(PHASE_TOTAL, build);
    }

    /** Record one completed-batch Parquet inner phase. Negative nanos are ignored. */
    public void recordBatchParquetPhase(String phase, long nanos) {
        recordPhase(batchParquetPhases, BATCH_PARQUET_PHASES, phase, nanos);
    }

    /** Time one completed-batch Parquet inner phase and return the supplier's value. */
    public <T> T timeBatchParquetPhase(String phase, Supplier<T> work) {
        requirePhase(BATCH_PARQUET_PHASES, phase);
        return batchParquetPhases.get(phase).record(work);
    }

    /** Time one completed-batch Parquet inner phase with no return value. */
    public void timeBatchParquetPhase(String phase, Runnable work) {
        requirePhase(BATCH_PARQUET_PHASES, phase);
        batchParquetPhases.get(phase).record(work);
    }

    /** Time one per-segment egress cycle (download + write + upload). */
    public <T> T timeEgress(Supplier<T> work) {
        return timeEgressPhase(PHASE_TOTAL, work);
    }

    /** Time one per-segment egress cycle with no return value. */
    public void timeEgress(Runnable work) {
        timeEgressPhase(PHASE_TOTAL, work);
    }

    /** Record one per-segment egress inner phase. Negative nanos are ignored. */
    public void recordEgressPhase(String phase, long nanos) {
        recordPhase(egressPhases, EGRESS_PHASES, phase, nanos);
    }

    /** Time one per-segment egress inner phase and return the supplier's value. */
    public <T> T timeEgressPhase(String phase, Supplier<T> work) {
        requirePhase(EGRESS_PHASES, phase);
        return egressPhases.get(phase).record(work);
    }

    /** Time one per-segment egress inner phase with no return value. */
    public void timeEgressPhase(String phase, Runnable work) {
        requirePhase(EGRESS_PHASES, phase);
        egressPhases.get(phase).record(work);
    }

    /** Record one checkpoint inner phase. Negative nanos are ignored. */
    public void recordCheckpointPhase(String phase, long nanos) {
        recordPhase(checkpointPhases, CHECKPOINT_PHASES, phase, nanos);
    }

    /** Time one checkpoint inner phase and return the supplier's value. */
    public <T> T timeCheckpointPhase(String phase, Supplier<T> work) {
        requirePhase(CHECKPOINT_PHASES, phase);
        return checkpointPhases.get(phase).record(work);
    }

    /** Time one checkpoint inner phase with no return value. */
    public void timeCheckpointPhase(String phase, Runnable work) {
        requirePhase(CHECKPOINT_PHASES, phase);
        checkpointPhases.get(phase).record(work);
    }

    private static void recordPhase(Map<String, Timer> timers, Set<String> allowed,
                                    String phase, long nanos) {
        requirePhase(allowed, phase);
        if (nanos < 0) {
            return;
        }
        timers.get(phase).record(nanos, TimeUnit.NANOSECONDS);
    }

    private static void requirePhase(Set<String> allowed, String phase) {
        if (!allowed.contains(phase)) {
            throw new IllegalArgumentException("Unknown duration phase: " + phase);
        }
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
        return timeCheckpointPhase(PHASE_TOTAL, build);
    }

    /** A changelog segment's delta Parquet egress was materialized. */
    public void segmentEgressed() {
        egressSegments.increment();
    }
}
