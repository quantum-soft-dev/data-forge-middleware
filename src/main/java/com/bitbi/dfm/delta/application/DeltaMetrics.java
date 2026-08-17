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
 *       {@code reason=frame_too_large|lossy_refold|history_gone}; the pointer does not move, so
 *       retention freezes with it, and no cause repairs itself unaided. <b>Alert on the meter, not
 *       on one tag</b> — that promise is the reason every permanent abort is registered here</li>
 *   <li>{@code delta.checkpoint.builds.deferred} — builds that did not run because another build
 *       held the process's fold budget (issue #178). Untagged, and deliberately <b>not</b> a value
 *       on {@code builds.aborted}: this one repairs itself as soon as the neighbour finishes</li>
 *   <li>{@code delta.checkpoint.fold.wait} — how long a build waited for the process's fold budget
 *       (issue #178). The band below {@code delta.checkpoint.fold-wait-seconds}, and the only
 *       series that shows contention short of a deferral</li>
 *   <li>{@code delta.checkpoint.fold.bytes} — peak estimated heap of one build's folded state,
 *       the band below {@code delta.checkpoint.max-fold-bytes} (issue #152)</li>
 *   <li>{@code delta.checkpoint.tables.given-up} — gauge: checkpoint rows the nightly
 *       rematerialize has stopped retrying (issue #149, see {@code CheckpointGivenUpMetrics})</li>
 *   <li>{@code delta.parquet.scratch.bytes} — gauge: bytes of file-backed Parquet scratch reserved
 *       by live writers, the band below {@code delta.parquet.max-scratch-bytes} (issue #150,
 *       registered in {@code ParquetScratchBudget}). It follows the writers even when no budget is
 *       configured, which is how that key is sized before it is turned on</li>
 *   <li>{@code delta.parquet.scratch.refused} — writers stopped because the shared scratch
 *       directory was full, tagged {@code writer=checkpoint_frame|checkpoint_table|batch_artifact}.
 *       Transient by nature — it says the directory was busy, not that the artifact was too big —
 *       so it is its own counter rather than a value on any of the meters below, neither the
 *       permanent {@code builds.aborted} nor the per-table {@code tables.unmaterialized}</li>
 *   <li>{@code delta.s3.read-denied} — objects whose presence S3 refused to answer, the HEAD and
 *       the one-key listing alike (issue #157, registered in {@code S3CheckpointStorage}). A rising
 *       count is an IAM or bucket-policy read outage; the work depending on those objects is skipped
 *       rather than concluded, so there is nothing to undo once the permission is back</li>
 *   <li>{@code delta.s3-orphan.candidates} / {@code delta.s3-orphan.reclaimed} /
 *       {@code delta.s3-orphan.delete-failed} — objects the orphan sweep found unreferenced, then
 *       deleted or could not delete, tagged {@code prefix=segments|checkpoints} (issue #158).
 *       Alert on {@code candidates}: it is the only one of the three that moves while
 *       {@code delta.s3-orphan.dry-run} is on, which is the shipped default</li>
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
    private static final String ORPHAN_PREFIX_TAG = "prefix";
    /** Tag value for {@code delta/{siteId}/segments/}. */
    public static final String ORPHAN_PREFIX_SEGMENTS = "segments";
    /** Tag value for {@code checkpoints/{siteId}/}. */
    public static final String ORPHAN_PREFIX_CHECKPOINTS = "checkpoints";
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
    private final Counter checkpointHistoryGone;
    private final Counter checkpointFoldTooLarge;
    private final Counter checkpointBuildsDeferred;
    private final Timer checkpointFoldWait;
    private final DistributionSummary checkpointFoldBytes;
    private final Counter batchParquetReady;
    private final Counter batchParquetFailed;
    private final Counter batchParquetAbandoned;
    private final Counter batchParquetReclaims;
    private final Counter segmentOrphanCandidates;
    private final Counter checkpointOrphanCandidates;
    private final Counter segmentOrphansReclaimed;
    private final Counter checkpointOrphansReclaimed;
    private final Counter segmentOrphanDeletesFailed;
    private final Counter checkpointOrphanDeletesFailed;
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
        this.checkpointHistoryGone = checkpointBuildAborted(registry, "history_gone");
        this.checkpointFoldTooLarge = checkpointBuildAborted(registry, "fold_too_large");
        this.checkpointBuildsDeferred = Counter.builder("delta.checkpoint.builds.deferred")
                .description("Checkpoint builds that did not run because another build held the "
                        + "process's fold budget")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.checkpointFoldWait = Timer.builder("delta.checkpoint.fold.wait")
                .description("Time a checkpoint build spent waiting for the process's fold budget")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.checkpointFoldBytes = DistributionSummary.builder("delta.checkpoint.fold.bytes")
                .description("Peak estimated heap held by one checkpoint build's folded state")
                .baseUnit("bytes")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.batchParquetReady = batchParquetOutcome(registry, "ready");
        this.batchParquetFailed = batchParquetOutcome(registry, "failed");
        this.batchParquetAbandoned = batchParquetOutcome(registry, "abandoned");
        this.batchParquetReclaims = Counter.builder("delta.batch-parquet.reclaims")
                .description("Completed-batch Parquet claims taken over after their lease expired")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).register(registry);
        this.segmentOrphanCandidates = orphanCandidates(registry, ORPHAN_PREFIX_SEGMENTS);
        this.checkpointOrphanCandidates = orphanCandidates(registry, ORPHAN_PREFIX_CHECKPOINTS);
        this.segmentOrphansReclaimed = orphansReclaimed(registry, ORPHAN_PREFIX_SEGMENTS);
        this.checkpointOrphansReclaimed = orphansReclaimed(registry, ORPHAN_PREFIX_CHECKPOINTS);
        this.segmentOrphanDeletesFailed = orphanDeletesFailed(registry, ORPHAN_PREFIX_SEGMENTS);
        this.checkpointOrphanDeletesFailed = orphanDeletesFailed(registry, ORPHAN_PREFIX_CHECKPOINTS);
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

    private static Counter orphanCandidates(MeterRegistry registry, String prefix) {
        return Counter.builder("delta.s3-orphan.candidates")
                .description("S3 objects found unreferenced by the orphan sweep, by prefix, "
                        + "whether or not it was allowed to delete them")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag(ORPHAN_PREFIX_TAG, prefix).register(registry);
    }

    private static Counter orphansReclaimed(MeterRegistry registry, String prefix) {
        return Counter.builder("delta.s3-orphan.reclaimed")
                .description("S3 objects deleted because no row referenced them, by prefix")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag(ORPHAN_PREFIX_TAG, prefix).register(registry);
    }

    private static Counter orphanDeletesFailed(MeterRegistry registry, String prefix) {
        return Counter.builder("delta.s3-orphan.delete-failed")
                .description("Unreferenced S3 objects the bucket would not delete, by prefix")
                .tag(APP_TAG_KEY, APP_TAG_VALUE).tag(ORPHAN_PREFIX_TAG, prefix).register(registry);
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
     * <p>A table stopped by the shared scratch <em>directory</em> (issue #150) is deliberately
     * <b>not</b> here. That refusal is systemic — the directory is full of other writers' files, so
     * every remaining table would meet it too — and it ends the build rather than recording a
     * verdict on one table, the same answer an unusable scratch directory has had since #112.
     * {@code delta.parquet.scratch.refused} is its meter.</p>
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
     * outcome of an operator action, not a frozen pointer. A build deferred behind the process's
     * fold budget (issue #178) is absent for the first of those reasons and has
     * {@link #checkpointBuildDeferred()} of its own.</p>
     *
     * <p><b>The caveat that used to sit here is gone (issue #157), and with it the reason to read
     * this meter per site before believing it.</b> {@code S3CheckpointStorage} used to answer a
     * {@code 403} as absence — correct for a missing key under least-privilege IAM, and
     * indistinguishable from a blanket read denial on keys that exist, so one bucket-policy change
     * made every pruned-history site trip {@code lossy_refold} in the same tick. A denied HEAD is
     * now resolved by a one-key listing, and a denial that survives it answers {@code UNKNOWN}:
     * the build skips that site without touching anything and increments {@code delta.s3.read-denied}
     * instead of this meter. A non-zero rate here is therefore a fact about the site's own data
     * again — and a rising {@code delta.s3.read-denied} is the permissions incident, plainly
     * labelled.</p>
     *
     * <p>{@code history_gone} is the third and the narrowest: the seed frame is gone and the
     * site has <b>no</b> segments at all, so there is no history to refold, lossily or otherwise —
     * the frame was the whole of that site's checkpoint history. It is split from
     * {@code lossy_refold} because the operator's next move differs: a lossy refold is about data
     * that still exists and may be an IAM incident, while this one is only recoverable by a
     * re-baseline or a history wipe (issue #149). Such a site is visited by the tick only for its
     * unmaterialized rows, so unlike the other two this abort does stop by itself, once those rows
     * have spent {@code delta.checkpoint.max-materialize-attempts} —
     * {@code delta.checkpoint.tables.given-up} is where it is visible afterwards.</p>
     *
     * <p>{@code fold_too_large} is the fourth, and it is about heap rather than disk or S3 (issue
     * #152): the site's folded state grew past {@code delta.checkpoint.max-fold-bytes}, the ceiling
     * that exists so a site too large to fold is refused instead of taking the whole pod down with
     * an {@code OOMKill}. It repairs itself no more than {@code frame_too_large} does — a site's
     * history does not shrink on its own — and the fix is the same shape: raise the key together
     * with the pod's heap, or re-baseline the site so its fold starts from what the source still
     * holds.</p>
     *
     * @param reason {@code frame_too_large}, {@code lossy_refold}, {@code history_gone} or
     *               {@code fold_too_large}
     */
    public void checkpointBuildAborted(String reason) {
        switch (reason) {
            case "frame_too_large" -> checkpointFrameTooLarge.increment();
            case "lossy_refold" -> checkpointLossyRefold.increment();
            case "history_gone" -> checkpointHistoryGone.increment();
            case "fold_too_large" -> checkpointFoldTooLarge.increment();
            default -> throw new IllegalArgumentException("Unknown reason: " + reason);
        }
    }

    /**
     * A checkpoint build did not run because another build held the process's fold budget
     * (issue #178).
     *
     * <p>Its own meter rather than a fifth {@code reason} on
     * {@link #checkpointBuildAborted(String)}, and the distinction is the one that meter's contract
     * rests on: every value there is a refusal that <b>never repairs itself</b>, which is what makes
     * a non-zero rate a page. This one repairs itself the moment the neighbouring build finishes —
     * it says nothing about the deferred site's own data, spends no materialize attempt and writes
     * nothing — so putting it there would make the page fire for a condition that clears on its
     * own, and an operator who learned to ignore it would ignore the four that matter with it.</p>
     *
     * <p>Untagged: there is one way to be deferred, and Prometheus cannot mix a tagged and an
     * untagged series under one name, so a later cause would take the tag then. What a sustained
     * rate means is that the nightly sweep and forced rebuilds are colliding for longer than
     * {@code delta.checkpoint.fold-wait-seconds} — raise that key, or find the build that is not
     * finishing.</p>
     */
    public void checkpointBuildDeferred() {
        checkpointBuildsDeferred.increment();
    }

    /**
     * Record how long a build waited for the process's fold budget (issue #178).
     *
     * <p>The counter above only fires once the wait is <em>spent</em>. This is the band below it,
     * and without it that band is invisible: the budget is taken outside {@code phase=total}, so a
     * build that waited nine minutes and then ran looks, on {@code delta.checkpoint.duration},
     * exactly like one that waited none — and {@code builds.deferred} stays at zero, because it did
     * run. {@code max(delta_checkpoint_fold_wait_seconds_max)} against
     * {@code delta.checkpoint.fold-wait-seconds} is how that key is sized before the first
     * deferral, the same reason {@code delta.checkpoint.fold.bytes} exists beside
     * {@code max-fold-bytes}.</p>
     *
     * <p>Recorded once per attempt at the budget, deferrals included, so the count is builds that
     * reached the fold rather than builds that finished it. An uncontended build records a sample
     * near zero on purpose: a series that only appeared under contention could not be told from a
     * scrape that lost it.</p>
     *
     * @param nanos time spent waiting; negative is ignored
     */
    public void recordCheckpointFoldWait(long nanos) {
        if (nanos >= 0) {
            checkpointFoldWait.record(nanos, TimeUnit.NANOSECONDS);
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

    /**
     * Objects the orphan sweep found unreferenced, whatever it was then allowed to do with them
     * (issue #158).
     *
     * <p>The series that works in both modes, and the reason it exists: with
     * {@code delta.s3-orphan.dry-run} on — the shipped default — {@link #s3OrphansReclaimed} stays
     * flat at zero for ever, so an alert written on a flat {@code reclaimed} would fire on a
     * healthy deployment that simply has not been switched to deleting yet. This one is how the
     * population is sized before that switch, and afterwards
     * {@code candidates - reclaimed - delete-failed} is zero by construction.</p>
     *
     * <p><b>A census, not an arrival rate</b>, whenever the sweep is not deleting: nothing removes
     * the backlog between passes, so the same objects are counted again every day and a rate alert
     * would read a static backlog as that many new orphans a day.</p>
     *
     * @param prefix {@link #ORPHAN_PREFIX_SEGMENTS} or {@link #ORPHAN_PREFIX_CHECKPOINTS}
     * @param count  how many unreferenced objects were found
     */
    public void s3OrphanCandidates(String prefix, long count) {
        orphanCounter(prefix, segmentOrphanCandidates, checkpointOrphanCandidates).increment(count);
    }

    /**
     * Objects reclaimed from one delta prefix because no row referenced them (issue #158).
     *
     * <p>Tagged by prefix rather than counted once, because the two populations mean different
     * things. Under {@code segments} a steady rate is ingestion commits failing after their upload;
     * under {@code checkpoints} it is the ordinary superseded generation of every advancing build,
     * so a <em>zero</em> rate there on a busy fleet is the surprising reading, not a high one.</p>
     *
     * <p><b>Per replica</b>, like every counter here: the sweep is not serialized across the
     * cluster, so two replicas whose passes overlap both count the same objects (the loser's
     * {@code DeleteObjects} succeeds on an already-deleted key). Read a rate against the number of
     * replicas rather than as an object count.</p>
     *
     * @param prefix {@link #ORPHAN_PREFIX_SEGMENTS} or {@link #ORPHAN_PREFIX_CHECKPOINTS}
     * @param count  how many objects were deleted
     */
    public void s3OrphansReclaimed(String prefix, long count) {
        orphanCounter(prefix, segmentOrphansReclaimed, checkpointOrphansReclaimed).increment(count);
    }

    /**
     * Unreferenced objects this pass could not delete (issue #158).
     *
     * <p>Nothing is lost — the next sweep sees the same keys and tries again — so this is a
     * bucket-health signal rather than a data one. It rises together with the WARN naming the
     * prefix; a sustained non-zero rate is a permissions or availability problem, since the keys
     * themselves are already proven unreferenced.</p>
     *
     * <p>Counted as {@code candidates - deleted} rather than as the number of error entries
     * {@code deleteObjects} returned (raised in review): that call records one entry per failed
     * 1000-key chunk, so entries would report a bucket-wide denial as a trickle. The subtraction
     * makes this and {@link #s3OrphansReclaimed} sum to the candidate set.</p>
     *
     * @param prefix {@link #ORPHAN_PREFIX_SEGMENTS} or {@link #ORPHAN_PREFIX_CHECKPOINTS}
     * @param count  how many objects were left behind
     */
    public void s3OrphanDeletesFailed(String prefix, long count) {
        orphanCounter(prefix, segmentOrphanDeletesFailed, checkpointOrphanDeletesFailed)
                .increment(count);
    }

    private static Counter orphanCounter(String prefix, Counter segments, Counter checkpoints) {
        return switch (prefix) {
            case ORPHAN_PREFIX_SEGMENTS -> segments;
            case ORPHAN_PREFIX_CHECKPOINTS -> checkpoints;
            default -> throw new IllegalArgumentException("Unknown prefix: " + prefix);
        };
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

    /**
     * Record the peak estimated heap one checkpoint build's fold reached (issue #152).
     *
     * <p>The band <em>below</em> {@code delta.checkpoint.max-fold-bytes} is the only warning that
     * precedes a permanent abort, and a log line cannot be alerted on — the same reason
     * {@code delta.checkpoint.builds.aborted} exists at all (#153). Compare the maximum of this
     * series against the configured budget to see how much room the largest site still has; the
     * build also logs the number, and warns at 75%.</p>
     *
     * <p>It is the <b>peak</b>, not the size the fold ended at: the ceiling is enforced on the
     * running total, and a fold that rises and falls back is exactly the one whose end size says
     * nothing. Recorded once per build that folded anything — an idle visit that returns before the
     * fold records nothing, so the series counts folds rather than ticks. Estimated in the same
     * coarse per-row terms as the budget it is compared against
     * ({@code ChangelogFold.estimatedRetainedBytes}), so the two are wrong together or not at all;
     * it is not a JVM measurement and must not be read beside {@code jvm_memory_used_bytes} as if
     * it were one.</p>
     *
     * @param bytes estimated retained bytes at the fold's peak; negative is ignored
     */
    public void recordCheckpointFoldBytes(long bytes) {
        if (bytes >= 0) {
            checkpointFoldBytes.record(bytes);
        }
    }

    /** A changelog segment's delta Parquet egress was materialized. */
    public void segmentEgressed() {
        egressSegments.increment();
    }
}
