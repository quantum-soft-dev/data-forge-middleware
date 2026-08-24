package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PrunableSegmentView;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Prunes changelog segments that the durable checkpoint has subsumed (Delta Client v2 — 022,
 * CR §4 / §8.D).
 *
 * <p>A segment whose {@code last_seq ≤ last_checkpoint_seq} is fully baked into the checkpoint frame
 * (T3.5a), so it is no longer needed to reconstruct current state and may be pruned. The most recent
 * {@code delta.retention.audit-window-segments} below-checkpoint segments are retained as a forensic
 * / replay window; everything older is removed (S3 object + metadata row). Without this, the
 * changelog grows unbounded and the checkpoint model degrades to "fold the whole history".</p>
 *
 * <p><b>Pending queue work is not prunable (issue #212).</b> A below-checkpoint segment whose
 * {@code plugin_sql_at} or {@code egress_at} is still {@code NULL} is the durable entry of a work
 * queue — {@code DeltaSqlQueueService} and {@code DeltaEgressService} retry it exactly because the
 * row is still pending — so deleting it would lose that batch's SQL or delta Parquet permanently,
 * silently, with no audit row marking the moment of loss. Such a segment is skipped, counted on
 * {@code delta.retention.segments.held-back{reason=pending_plugin_sql|pending_egress}} and named by
 * one WARN per site per pass, so a stuck backlog is visible before it is large. The row delete is a
 * <b>single conditional statement</b> ({@code deleteByIdIfProcessed}) and the S3 object goes only
 * after it reported success: a plugin reinit re-{@code NULL}s {@code plugin_sql_at} site-wide
 * ({@code clearPluginSqlBySiteId}), so a check-then-act across statements would delete a
 * freshly-pending row — and its object first. The predicate cannot pin a segment forever by design
 * elsewhere: every segment is egressed regardless of plugin state (tables without a schema are
 * skipped but the segment is still marked), and the delta-SQL queue stamps {@code plugin_sql_at}
 * without generating for inactive activations and for {@code FULL_SNAPSHOT} baselines. Provisional
 * segments (033) are not this predicate's concern at all: the query above excludes them, and their
 * parked sentinel markers protect them from the <em>queues</em>, not from retention. Held-back
 * segments still count toward the audit window — the window keeps its meaning ("the most recent N
 * below-checkpoint segments"), and the hold-back retains segments on top of it rather than
 * re-shaping it.</p>
 *
 * <p><b>A pending completed-batch Parquet build is not prunable either (issue #244).</b> The
 * 036/038 finalization replays a batch's <em>raw</em> segments on every attempt, so a batch whose
 * artifact row is still {@link BatchParquetArtifactStatus#UNFINISHED} needs them — and the decision
 * is per <b>batch</b>, not per segment: pruning part of a batch would leave the replay silently
 * truncated (the row-count guard derives its expectation from the segments actually loaded) rather
 * than failed, which is worse than pruning all of it. Counted on
 * {@code delta.retention.segments.held-back{reason=pending_batch_parquet}} beside the two queue
 * markers. Unlike those, this hold-back is <b>bounded by construction</b>: an artifact row leaves
 * {@code UNFINISHED} after {@code delta.batch-parquet.max-attempts} attempts (~1 h), so it cannot
 * pin a segment indefinitely. The two windows it deliberately does not cover — an
 * {@code ABANDONED} row requeued later (039) and the legacy lazy backfill (037), neither of which
 * has an unfinished row while retention runs — do not fail silently either: the replay classifies a
 * pruned segment set as a <em>permanent</em> failure naming retention instead of retrying it for an
 * hour, the admin requeue refuses an unproducible artifact, and the backfill logs it.</p>
 *
 * <p><b>Deliberately no age or count bound of this pass's own on the hold-back.</b> The main
 * permanent-stall scenario — a mistyped {@code plugin.sql-generation.heap-threshold-percent} making
 * every generation refuse forever — is closed at source by #185's fail-fast validation (an
 * out-of-range value fails the context at startup, {@code PluginConfigValidation}), and a
 * deterministic poison batch is already loud through {@code sql.generation.errors} and
 * the #181 audit entries. A hold-back therefore ends only when something else legitimately takes
 * the segment: its queues draining it; an operator deleting the segment or its batch (the admin
 * batch delete logs the pending count it destroys); a client-initiated re-baseline or history wipe
 * replacing the site's history; or <b>batch retention — the deliberate outer horizon</b>
 * ({@code BatchRetentionService}, per-site {@code retentionDays}, default 45 days), which deletes a
 * retired batch's segments regardless of their markers, counting and WARNing when they still carried
 * pending work. That horizon is also what bounds the storage a permanently stuck segment can pin.</p>
 *
 * <p><b>The pass opens no transaction of its own (issue #234).</b> It used to be
 * {@code @Transactional} around everything, so the batched {@code DeleteObjects} round trip ran
 * with the pass's transaction — and every row lock it had taken — still open, on a scheduler
 * thread, per site, serially, for a hold proportional to the backlog. Instead the projection read
 * and each conditional row delete are the repository's own short transactions (so a connection is
 * released between statements, which is what makes a pool smaller than its callers safe — #161),
 * and the objects go afterwards with nothing open. The ordering is the same one #158 documented and
 * is what makes a crash mid-pass converge: the row first, its object only after the delete reported
 * success, so the worst outcome is an unreferenced object the orphan sweep reclaims — never a row
 * pointing at an object that is gone. Partial progress now stands where it used to roll back, which
 * is the intended direction: a pruned row is durable work, not a step of one atomic pass.</p>
 *
 * <p>This pass runs only after a <em>successful</em> checkpoint build ({@code CheckpointScheduler}),
 * so the held-back series has a blind spot its readers must know: a site whose build aborts nightly
 * shows zero here while its backlog accumulates — {@code delta.checkpoint.builds.aborted} is the
 * series that covers that state.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ChangelogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ChangelogRetentionService.class);

    /**
     * Keys buffered before an object delete goes out.
     *
     * <p>1000 is {@code S3FileStorageService}'s own chunk size today, which is why the flush costs
     * no extra round trip. The two are deliberately not coupled — that constant is private to
     * another aggregate's infrastructure — and drift is harmless: a smaller chunk there simply
     * splits one flush into two, the bound this value exists for (how many objects a pod kill can
     * strand) being unaffected.</p>
     */
    private static final int OBJECT_DELETE_CHUNK = 1000;
    /**
     * How many batch ids one census query may carry (issue #244, review round 1). The
     * below-checkpoint set is unbounded since #212, so a single {@code IN} list over every
     * candidate batch can exceed PostgreSQL's 32767 bind parameters — which would fail the whole
     * pass for exactly the backlog it exists to clear.
     */
    private static final int CENSUS_CHUNK = 1000;

    private final ChangelogSegmentRepository segmentRepository;
    private final BatchParquetArtifactRepository artifactRepository;
    private final S3FileStorageService objectDeleter;
    private final DeltaSyncStateService syncStateService;
    private final DeltaMetrics metrics;
    private final int auditWindowSegments;

    public ChangelogRetentionService(ChangelogSegmentRepository segmentRepository,
                                     BatchParquetArtifactRepository artifactRepository,
                                     S3FileStorageService objectDeleter,
                                     DeltaSyncStateService syncStateService,
                                     DeltaMetrics metrics,
                                     @Value("${delta.retention.audit-window-segments:20}") int auditWindowSegments) {
        this.segmentRepository = segmentRepository;
        this.artifactRepository = artifactRepository;
        this.objectDeleter = objectDeleter;
        this.syncStateService = syncStateService;
        this.metrics = metrics;
        this.auditWindowSegments = Math.max(0, auditWindowSegments);
    }

    /**
     * Prune below-checkpoint segments for a site, keeping the audit window and holding back any
     * segment whose plugin SQL or egress is still pending (issue #212).
     *
     * @param siteId site identifier
     * @return number of segments pruned (held-back segments are not pruned and not counted here)
     * @throws IllegalStateException if a transaction is already open (issue #234)
     */
    public int prune(UUID siteId) {
        refuseInsideTransaction();
        long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
        if (checkpointSeq <= 0) {
            return 0;
        }

        // Light projections, oldest first — id, key and the two queue markers, never the entity:
        // with pending segments held back this set is no longer bounded by the audit window.
        List<PrunableSegmentView> belowCheckpoint =
                segmentRepository.findBelowCheckpointBySiteId(siteId, checkpointSeq);

        int pruneCount = Math.max(0, belowCheckpoint.size() - auditWindowSegments);
        // One census read per pass, over the candidate batches only (issue #244) — never one query
        // per segment, and never per site: a batch is the unit of the decision.
        Set<UUID> batchesOwedParquet = batchesOwedParquet(belowCheckpoint, pruneCount);
        List<String> pendingObjectDeletes = new ArrayList<>();
        HeldBackTally heldBack = new HeldBackTally();
        int prunedRows = 0;
        boolean unwinding = true;
        // The delete is in a finally because the pass is no longer one transaction (issue #234,
        // review round 1): a failure inside the loop — a lock timeout on one row, a pool timeout, a
        // failover — leaves every row deleted so far committed, and their keys would otherwise
        // never reach S3. The #158 orphan sweep is the backstop for a crash, not the plan for an
        // ordinary exception: it ships dry-run by default, so its reclaim is inert until an
        // operator turns it on.
        try {
            for (int i = 0; i < pruneCount; i++) {
                PrunableSegmentView segment = belowCheckpoint.get(i);
                if (heldBack.count(segment.isPendingPluginSql(), segment.isPendingEgress(),
                        batchesOwedParquet.contains(segment.getBatchId()))) {
                    continue;
                }
                if (segmentRepository.deleteByIdIfProcessed(
                        segment.getId(), BatchParquetArtifactStatus.UNFINISHED) == 1) {
                    // Row first, object after the row delete reported success: a crash in between
                    // leaves an unreferenced object for the #158 orphan sweep, never a row whose
                    // object is gone.
                    pendingObjectDeletes.add(segment.getS3Key());
                    prunedRows++;
                    if (pendingObjectDeletes.size() >= OBJECT_DELETE_CHUNK) {
                        // Flushed during the pass, not only at the end (review round 4): a pod kill
                        // between a row's commit and the end of the loop strands its object, and
                        // the sweep that would reclaim it ships dry-run, so the exposure is bounded
                        // at one chunk. No extra round trip: deleteObjects chunks at 1000 too.
                        // Taken out of the buffer before the delete, not after (review round 8):
                        // an Error escaping the flush would otherwise leave the keys in the buffer
                        // for the finally to delete a second time. The copy is needed regardless —
                        // deleteObjects builds subList views over what it is handed.
                        List<String> chunk = List.copyOf(pendingObjectDeletes);
                        pendingObjectDeletes.clear();
                        deletePrunedObjects(siteId, chunk);
                    }
                    continue;
                }
                // The row read as prunable above but the conditional delete refused it: a reinit
                // committed in between and re-pended it, a lazy backfill or an admin requeue
                // created the artifact row that needs it (issue #244), or another deleter took it.
                // Re-read and count it by what the row says now; a row that vanished counts
                // nowhere, and a row whose markers are still done can only have been refused by
                // the artifact predicate the same statement carries.
                segmentRepository.findById(segment.getId()).ifPresent(refused ->
                        heldBack.count(refused.isPendingPluginSql(), refused.isPendingEgress(),
                                !refused.isPendingPluginSql() && !refused.isPendingEgress()));
            }
            unwinding = false;
        } finally {
            final int prunedRowsSoFar = prunedRows;
            // Everything this pass did is reported from the finally, because everything it did is
            // durable (issue #234, review round 2): a throw mid-loop leaves rows deleted and their
            // objects deleted with them, so leaving the counters and the lines past the throw would
            // make an aborted pass read as "nothing happened" — and the #212 stuck-backlog alarm
            // would read zero for a pass that did observe held-back segments.
            if (unwinding) {
                // Nothing that runs here may replace the exception on its way out — the logging
                // inside these two methods included, which is the one masking route the previous
                // round's comment claimed to have closed and had not (review round 5). There is
                // nowhere left to report a failure of the reporting itself.
                swallowing(() -> deletePrunedObjects(siteId, pendingObjectDeletes));
                swallowing(() -> reportPass(siteId, checkpointSeq, prunedRowsSoFar, heldBack));
            } else {
                // Symmetric protection for the one class neither method absorbs: a LinkageError
                // from the SDK or Micrometer during teardown would escape prune past
                // CheckpointScheduler's catch (RuntimeException) and end the remaining sites of
                // the tick (review round 8). Here there is no in-flight exception to protect, so
                // it is logged rather than swallowed.
                withoutEndingTheTick(siteId, () -> deletePrunedObjects(siteId, pendingObjectDeletes));
                withoutEndingTheTick(siteId, () -> reportPass(siteId, checkpointSeq, prunedRowsSoFar, heldBack));
            }
        }

        return prunedRows;
    }

    /**
     * Delete the objects of the rows this pass has already removed (issue #234).
     *
     * <p>One {@code DeleteObjects} round trip per 1000 keys instead of one per object, and with no
     * transaction open. Errors are summarized, not thrown: the rows are gone, so a failed object
     * delete leaves the same unreferenced litter the #158 sweep reclaims, while a throw would end
     * the pass and report a healthy prune to {@code CheckpointScheduler} as this site's failure.
     * {@code deleteObjects} catches {@code S3Exception} per chunk but not {@code SdkClientException}
     * (the gap #158 round 2 documented), which is why the catch here is broader than the summarized
     * errors — and why it must not mask an exception the loop is already unwinding with.</p>
     *
     * <p>Called once per full 1000-key chunk during the pass and once for the remainder, so its
     * count is <b>this chunk's</b>, not the pass's — the lines say so (review round 5).</p>
     *
     * @param siteId     the site being pruned, for the log line
     * @param prunedKeys this chunk's keys, whose row delete reported success; may be empty
     */
    private void deletePrunedObjects(UUID siteId, List<String> prunedKeys) {
        if (prunedKeys.isEmpty()) {
            return;
        }
        try {
            S3FileStorageService.DeleteObjectsResult result = objectDeleter.deleteObjects(prunedKeys);
            if (!result.errors().isEmpty()) {
                log.warn("Deleting the objects of {} pruned changelog segment row(s) for site {} "
                                + "reported {} failure(s) — those objects are unreferenced and the "
                                + "S3 orphan sweep reclaims them (issue #158)",
                        prunedKeys.size(), siteId, result.errors().size());
            }
        } catch (RuntimeException e) {
            log.warn("Deleting the objects of {} pruned changelog segment row(s) for site {} "
                            + "failed mid-way — the undeleted objects are unreferenced and the "
                            + "S3 orphan sweep reclaims them (issue #158)",
                    prunedKeys.size(), siteId, e);
        }
    }

    /**
     * Report what the pass did — the #212 counters and the two lines (issue #234, review round 3).
     *
     * <p>Each step is attempted independently, and a failure of the reporting itself — a Micrometer
     * name/tag conflict, a failing appender during a rollout — is a reporting failure and is logged
     * as one. It is deliberately neither
     * silent (half an emitted alarm with no error anywhere is worse than a loud one, review round 4)
     * nor rethrown (that reached {@code CheckpointScheduler}'s catch, which logs "Checkpoint
     * build/retention failed" for a site whose checkpoint was built and whose rows were pruned,
     * review round 5). While the loop is unwinding the caller runs this through
     * {@link #swallowing(Runnable)} instead, because there the only thing left to lose is the
     * exception on its way out.</p>
     */
    private void reportPass(UUID siteId, long checkpointSeq, int prunedCount, HeldBackTally heldBack) {
        // Step by step, so one broken step does not take the rest with it: a throw from the first
        // counter used to skip the second one and both lines, which is the "half-emitted alarm"
        // round 4 objected to, reached by a different route (review round 6).
        List<Runnable> steps = List.of(
                () -> metrics.retentionSegmentsHeldBack(
                        DeltaMetrics.RETENTION_PENDING_PLUGIN_SQL, heldBack.pendingPluginSql),
                () -> metrics.retentionSegmentsHeldBack(
                        DeltaMetrics.RETENTION_PENDING_EGRESS, heldBack.pendingEgress),
                () -> metrics.retentionSegmentsHeldBack(
                        DeltaMetrics.RETENTION_PENDING_BATCH_PARQUET, heldBack.pendingBatchParquet),
                () -> {
                    if (heldBack.segments > 0) {
                        log.warn("Held back {} below-checkpoint segment(s) with pending work for "
                                        + "site {} — {} awaiting plugin SQL, {} awaiting egress, "
                                        + "{} awaiting the completed-batch Parquet build; retention "
                                        + "does not delete unprocessed work (issues #212, #244)",
                                heldBack.segments, siteId, heldBack.pendingPluginSql,
                                heldBack.pendingEgress, heldBack.pendingBatchParquet);
                    }
                },
                () -> {
                    if (prunedCount > 0) {
                        log.info("Pruned {} changelog segment(s) below checkpoint@{} for site {} "
                                        + "(audit window {})",
                                prunedCount, checkpointSeq, siteId, auditWindowSegments);
                    }
                });

        RuntimeException failure = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            // Not silent (review round 4: half an alarm with no error anywhere is worse than a
            // loud one) and not this site's retention failure either (review round 5: rethrowing
            // reached CheckpointScheduler's catch, which logs "Checkpoint build/retention failed"
            // for a site whose checkpoint was built and whose rows were pruned). The wording does
            // not claim the pass completed: this also runs while the loop is unwinding (round 6).
            RuntimeException reported = failure;
            swallowing(() -> log.warn("Reporting the retention pass for site {} failed; {} "
                    + "segment(s) had been pruned by that point", siteId, prunedCount, reported));
        }
    }

    /**
     * Run a {@code finally} step that must not throw, whatever it does (review round 5).
     *
     * @param step the reporting or cleanup step to attempt
     */
    private static void swallowing(Runnable step) {
        try {
            step.run();
        } catch (VirtualMachineError fatal) {
            // The one class that must still win: the JVM is not in a state where finishing this
            // pass's bookkeeping means anything.
            throw fatal;
        } catch (Throwable ignored) {
            // Deliberate, and Throwable rather than RuntimeException (review round 6): during
            // context teardown this path can raise a NoClassDefFoundError from an SDK being torn
            // down under it, which would both replace the loop's exception and escape
            // CheckpointScheduler's catch (RuntimeException) — ending the whole nightly tick
            // instead of costing this one site.
        }
    }

    /**
     * Run a {@code finally} step on the successful path: it may fail, but it may not end the tick
     * (review round 8).
     *
     * @param siteId the site being pruned, for the log line
     * @param step   the reporting or cleanup step to attempt
     */
    private static void withoutEndingTheTick(UUID siteId, Runnable step) {
        try {
            step.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException e) {
            // Both steps already handle their own RuntimeExceptions; this is the belt.
            log.warn("Finishing the retention pass for site {} failed after the pruning itself "
                    + "succeeded", siteId, e);
        } catch (Throwable e) {
            log.warn("Finishing the retention pass for site {} failed after the pruning itself "
                    + "succeeded", siteId, e);
        }
    }

    /**
     * Refuse to prune inside a caller's transaction (issue #234).
     *
     * <p>The guard is what keeps the property from regressing silently: a caller that wrapped this
     * pass in a transaction would restore the connection hold across the object deletes while every
     * assertion about what is pruned still passed. Checked before anything is read, so the failure
     * names the wiring rather than one site's data (the {@code DeltaEgressService} /
     * {@code DeltaSqlQueueService} shape of #164).</p>
     */
    private void refuseInsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Refusing to prune changelog segments inside an active transaction: the batched "
                            + "object delete would hold that transaction's connection and row locks "
                            + "for the length of a network call (issue #234).");
        }
    }

    /**
     * The distinct batches among the segments this pass would prune that still owe a
     * completed-batch Parquet build (issue #244).
     */
    private Set<UUID> batchesOwedParquet(List<PrunableSegmentView> belowCheckpoint, int pruneCount) {
        Set<UUID> candidates = new LinkedHashSet<>();
        for (int i = 0; i < pruneCount; i++) {
            candidates.add(belowCheckpoint.get(i).getBatchId());
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }
        // Chunked, because the candidate set is unbounded: one IN list over every batch of a
        // months-old backlog would exceed the driver's parameter limit and throw before a single
        // row was pruned.
        List<UUID> ordered = List.copyOf(candidates);
        Set<UUID> owed = new LinkedHashSet<>();
        for (int from = 0; from < ordered.size(); from += CENSUS_CHUNK) {
            owed.addAll(artifactRepository.findBatchIdsWithStatusIn(
                    ordered.subList(from, Math.min(from + CENSUS_CHUNK, ordered.size())),
                    BatchParquetArtifactStatus.UNFINISHED));
        }
        return owed;
    }

    /** The hold-back census of one pass — the same counting for the view and the re-read (R2-9). */
    private static final class HeldBackTally {

        private int segments;
        private int pendingPluginSql;
        private int pendingEgress;
        private int pendingBatchParquet;

        /** @return {@code true} when the segment owed work (and has now been counted) */
        private boolean count(boolean pluginSqlPending, boolean egressPending,
                              boolean batchParquetPending) {
            if (!pluginSqlPending && !egressPending && !batchParquetPending) {
                return false;
            }
            segments++;
            if (pluginSqlPending) {
                pendingPluginSql++;
            }
            if (egressPending) {
                pendingEgress++;
            }
            if (batchParquetPending) {
                pendingBatchParquet++;
            }
            return true;
        }
    }
}
