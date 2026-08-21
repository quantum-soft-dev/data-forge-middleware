package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The first bound on the <em>sum</em> of the file-backed Parquet scratch (issue #150).
 *
 * <p>Every guard before this one bounded a single file — {@code delta.checkpoint.max-temp-bytes} one
 * table snapshot, {@code delta.checkpoint.max-frame-temp-bytes} one reload frame,
 * {@code delta.batch-parquet.max-temp-bytes} one completed-batch artifact — while the thing that
 * evicts the pod is the directory they share. #138 set the deployed ceilings to a third of the
 * volume each, which is a floor on the guarantee rather than a budget: the <b>number</b> of
 * simultaneous files is not bounded by any per-file key. A completed-batch build opens one scratch
 * file per claimed table (#038), so a ten-table batch puts ten files on the volume however low that
 * ceiling is set, and the penalty for filling it is a kubelet eviction — no skip, no metric,
 * in-flight ingest dies with the pod.</p>
 *
 * <p><b>Charged as bytes are written, not reserved at the ceiling.</b> The ticket's other option was
 * to reserve each writer's per-file ceiling up front, which chooses its victim more crisply but
 * cannot work here: the deployed ceilings are 1 GiB against artifacts in the low hundreds of MiB, so
 * a three-table batch would reserve 3 GiB it will not use and be refused for ever on a 5 GiB budget.
 * Charging as the bytes actually land makes the budget describe the disk rather than the
 * configuration, and a writer that crosses it is stopped mid-file exactly as
 * {@link CappedOutputStream} and {@code FileOutputFile} already stop one that crosses its own
 * ceiling.</p>
 *
 * <p><b>What that costs, said plainly.</b> A shared running total cannot choose which writer to
 * refuse — the byte that crosses it belongs to whoever happens to be writing one — so two large
 * writers can each take half and both be refused where either alone would have fitted. That is the
 * objection #178 raised against a shared total for the heap twin of this budget, and the answer here
 * is different only because the alternative is: heap could be made exclusive because one fold at a
 * time is a real mode of operation, while a batch build genuinely needs one open file per claimed
 * table and cannot be serialized without replaying the segments once per table (the multiplier #038
 * removed). What carries over unchanged is the rule about <em>reporting</em>: a refusal here is
 * transient, so it never appears as a tag value on a meter contracted to mean permanent —
 * {@link ScratchBudgetExceededException} is its own type and
 * {@code delta.parquet.scratch.refused} is its own counter.</p>
 *
 * <p><b>Unbounded by default</b> ({@code delta.parquet.max-scratch-bytes: 0}), because the
 * application cannot know how large the directory it was handed is — the same reason #138 left the
 * per-file defaults at 10 GiB and declared the deployed values beside the volume in
 * {@code k8s/base/configmap.yaml}. Unbounded still <b>measures</b>: {@code delta.parquet.scratch.bytes}
 * follows the writers whether or not a budget is set, which is the only way an operator can size the
 * key before turning it on.</p>
 *
 * <p><b>A reserved share for the checkpoint path</b> (issue #193). Batch writers may use at most
 * {@code max-scratch-bytes} minus {@code delta.checkpoint.max-frame-temp-bytes} — the declared size
 * of the largest scratch file the checkpoint path holds, and it holds only one at a time (#178).
 * That is a floor for the nightly sweep, not a ceiling: a checkpoint writer can still use the whole
 * budget when the directory is idle. Batch cannot consume into the reserved bytes even after the
 * frame is deleted, which is the gap before the table snapshot opens. Unbounded (the shipped
 * default) has nothing to reserve a share of, so a leftover frame-ceiling value is ignored.</p>
 *
 * <p><b>Per JVM</b>, so it is a true bound only where the directory is pod-private
 * ({@code delta.parquet.scratch-private-to-pod}, #141); on a shared volume it is a per-replica
 * share. Scratch left behind by a dead process (#127, #141) sits outside every lease, since its
 * owner is gone — which is why the deployed budget keeps a gigabyte of the volume in reserve.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class ParquetScratchBudget {

    private static final Logger log = LoggerFactory.getLogger(ParquetScratchBudget.class);

    /**
     * How much a lease reserves from the shared counter at a time.
     *
     * <p>The alternative is one atomic operation per {@code write(int)}, and gzip's frame writer
     * makes plenty of those. The price is an over-reservation of at most one chunk per live writer,
     * which against a budget measured in gibibytes is noise.</p>
     */
    static final long CHUNK_BYTES = 1024L * 1024;

    /**
     * The writers allowed to charge this budget.
     *
     * <p>A closed set rather than a free-form tag: {@code writer} is a Prometheus label, and a typo
     * would create a series nobody alerts on instead of failing. Each is registered at zero so an
     * alert can predate the first refusal.</p>
     */
    static final String CHECKPOINT_FRAME = "checkpoint_frame";
    static final String CHECKPOINT_TABLE = "checkpoint_table";
    static final String BATCH_ARTIFACT = "batch_artifact";

    private final AtomicLong liveBytes = new AtomicLong();
    private final Map<String, Counter> refusals = new LinkedHashMap<>();
    private final long budgetBytes;
    private final long checkpointReserveBytes;

    /**
     * Tests that do not exercise the checkpoint reserved share (issue #193).
     */
    public ParquetScratchBudget(MeterRegistry registry, long budgetBytes) {
        this(registry, budgetBytes, 0L);
    }

    @Autowired
    public ParquetScratchBudget(MeterRegistry registry,
                                @Value("${delta.parquet.max-scratch-bytes:0}") long budgetBytes,
                                @Value("${delta.checkpoint.max-frame-temp-bytes:0}") long checkpointReserveBytes) {
        // A negative value is read as unbounded rather than as "refuse everything": an operator who
        // typed one meant to relax the guard, and the failure mode of the other reading is every
        // checkpoint table skipped and every batch artifact retried to abandonment.
        this.budgetBytes = Math.max(0L, budgetBytes);
        // Unbounded has nothing to reserve a share of. A negative reserve is none. A reserve larger
        // than the budget leaves batch writers with zero — the nightly frame still gets the whole
        // directory, which is the point of the share.
        this.checkpointReserveBytes = this.budgetBytes == 0L
                ? 0L
                : Math.min(this.budgetBytes, Math.max(0L, checkpointReserveBytes));
        Gauge.builder("delta.parquet.scratch.bytes", liveBytes, AtomicLong::doubleValue)
                .description("Bytes of file-backed Parquet scratch reserved by live writers")
                .tag("application", "data-forge-middleware")
                .register(registry);
        for (String writer : new String[]{CHECKPOINT_FRAME, CHECKPOINT_TABLE, BATCH_ARTIFACT}) {
            refusals.put(writer, Counter.builder("delta.parquet.scratch.refused")
                    .description("Writers stopped because the shared scratch directory was full")
                    .tag("application", "data-forge-middleware")
                    .tag("writer", writer)
                    .register(registry));
        }
        if (this.budgetBytes == 0L) {
            log.info("The Parquet scratch directory is unbounded (delta.parquet.max-scratch-bytes "
                    + "is unset); delta.parquet.scratch.bytes measures it so the key can be sized");
        } else if (this.checkpointReserveBytes == 0L) {
            log.info("The Parquet scratch directory is bounded at {} bytes "
                    + "(delta.parquet.max-scratch-bytes)", this.budgetBytes);
        } else {
            log.info("The Parquet scratch directory is bounded at {} bytes "
                    + "(delta.parquet.max-scratch-bytes), of which {} bytes are reserved for a "
                    + "checkpoint frame (delta.checkpoint.max-frame-temp-bytes)",
                    this.budgetBytes, this.checkpointReserveBytes);
        }
    }

    /**
     * Take a lease on the shared directory for one scratch file.
     *
     * <p>The lease's life is the <b>file's</b> life, not the stream's: a completed-batch build closes
     * every writer and only then uploads them one by one, so the bytes are still on disk long after
     * the last {@code write}. Close it in the same {@code finally} that deletes the file.</p>
     *
     * @param writer one of the constants on this class; anything else is a programming error
     * @return a lease that charges this budget and releases it on {@link ScratchLease#close()}
     */
    public ScratchLease open(String writer) {
        Counter refused = refusals.get(writer);
        if (refused == null) {
            throw new IllegalArgumentException("Unknown scratch writer: " + writer);
        }
        return new BudgetedLease(writer, refused);
    }

    /**
     * One writer's share of the directory, held for the life of its scratch file.
     *
     * <p>Confined to the thread that writes the file — a completed-batch build opens one per claimed
     * table but writes them all from the single replay thread — so only the shared counter is
     * atomic.</p>
     */
    private final class BudgetedLease implements ScratchLease {

        private final String writer;
        private final Counter refused;
        private long granted;
        private long used;
        private boolean refusalCounted;
        private boolean closed;

        private BudgetedLease(String writer, Counter refused) {
            this.writer = writer;
            this.refused = refused;
        }

        @Override
        public void charge(long delta) {
            if (delta <= 0) {
                return;
            }
            // Advanced only once the room exists. Counting bytes a refusal is about to unwind
            // would leave this lease's idea of what is on disk permanently ahead of the file, and
            // the writers do not all stop at the first refusal — a Parquet writer's close() still
            // emits its footer — so a later charge would over-reserve against a gauge this budget
            // asks operators to size the key from.
            if (closed) {
                // Nothing is reachable through a closed lease, and re-reserving through one would
                // be permanent: `granted` is zero again, so the whole file's bytes would be taken
                // from the directory with nobody left to give them back, and the pod would run a
                // smaller budget than it was configured with until it restarted. Every writer today
                // is closed before its lease, so this is a guard on the contract rather than a live
                // path — but the lease is a published interface documented as outliving the writer,
                // and that is exactly the shape a future ordering slip takes (raised in review).
                return;
            }
            long wanted = used + delta;
            while (wanted > granted) {
                reserve(wanted - granted);
            }
            used = wanted;
        }

        @Override
        public void close() {
            closed = true;
            if (granted > 0) {
                liveBytes.addAndGet(-granted);
                granted = 0;
            }
        }

        private void reserve(long need) {
            long want = Math.max(need, CHUNK_BYTES);
            long ceiling = writerCeiling();
            while (true) {
                long current = liveBytes.get();
                long take = budgetBytes == 0L
                        ? want
                        : Math.min(want, Math.max(0L, ceiling - current));
                if (take < need) {
                    // Once per lease, not once per refused write. FileOutputFile does not latch
                    // the way CappedOutputStream does — Parquet unwinds a write failure through a
                    // close() that writes a footer — so a per-write increment would report two or
                    // more refusals for one refused artifact, and a different number for the frame.
                    // One file that could not be written is one refusal.
                    if (!refusalCounted) {
                        refusalCounted = true;
                        refused.increment();
                    }
                    // Reserve nothing on a refusal. The writer is about to be unwound and its file
                    // deleted, so holding the bytes it did not get would shrink the directory for
                    // every other writer until the pod restarts.
                    throw new ScratchBudgetExceededException(
                            writer, need, ceiling, budgetBytes, checkpointReserveBytes, current);
                }
                if (liveBytes.compareAndSet(current, current + take)) {
                    granted += take;
                    return;
                }
            }
        }

        /**
         * Batch writers stop at {@code budget - checkpoint reserve} so the nightly frame always
         * has somewhere to land (issue #193). Checkpoint writers still see the whole budget.
         */
        private long writerCeiling() {
            if (budgetBytes == 0L) {
                return 0L;
            }
            if (BATCH_ARTIFACT.equals(writer)) {
                return budgetBytes - checkpointReserveBytes;
            }
            return budgetBytes;
        }
    }
}
