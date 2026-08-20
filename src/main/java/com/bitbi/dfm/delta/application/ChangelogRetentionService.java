package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PrunableSegmentView;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
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

    private final ChangelogSegmentRepository segmentRepository;
    private final S3FileStorageService objectDeleter;
    private final DeltaSyncStateService syncStateService;
    private final DeltaMetrics metrics;
    private final int auditWindowSegments;

    public ChangelogRetentionService(ChangelogSegmentRepository segmentRepository,
                                     S3FileStorageService objectDeleter,
                                     DeltaSyncStateService syncStateService,
                                     DeltaMetrics metrics,
                                     @Value("${delta.retention.audit-window-segments:20}") int auditWindowSegments) {
        this.segmentRepository = segmentRepository;
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
        List<String> prunedKeys = new ArrayList<>();
        HeldBackTally heldBack = new HeldBackTally();
        // The delete is in a finally because the pass is no longer one transaction (issue #234,
        // review round 1): a failure inside the loop — a lock timeout on one row, a pool timeout, a
        // failover — leaves every row deleted so far committed, and their keys would otherwise
        // never reach S3. The #158 orphan sweep is the backstop for a crash, not the plan for an
        // ordinary exception: it ships dry-run by default, so its reclaim is inert until an
        // operator turns it on.
        try {
            for (int i = 0; i < pruneCount; i++) {
                PrunableSegmentView segment = belowCheckpoint.get(i);
                if (heldBack.countIfPending(segment.isPendingPluginSql(), segment.isPendingEgress())) {
                    continue;
                }
                if (segmentRepository.deleteByIdIfProcessed(segment.getId()) == 1) {
                    // Row first, object after the row delete reported success: a crash in between
                    // leaves an unreferenced object for the #158 orphan sweep, never a row whose
                    // object is gone.
                    prunedKeys.add(segment.getS3Key());
                    continue;
                }
                // The row read as processed above but the conditional delete refused it: a reinit
                // committed in between and re-pended it (or another deleter took it). Re-read and
                // count it by what the row says now; a row that vanished counts nowhere.
                segmentRepository.findById(segment.getId()).ifPresent(rePended ->
                        heldBack.countIfPending(rePended.isPendingPluginSql(), rePended.isPendingEgress()));
            }
        } finally {
            deletePrunedObjects(siteId, prunedKeys);
        }

        metrics.retentionSegmentsHeldBack(DeltaMetrics.RETENTION_PENDING_PLUGIN_SQL, heldBack.pendingPluginSql);
        metrics.retentionSegmentsHeldBack(DeltaMetrics.RETENTION_PENDING_EGRESS, heldBack.pendingEgress);
        if (heldBack.segments > 0) {
            log.warn("Held back {} below-checkpoint segment(s) with pending work for site {} — "
                            + "{} awaiting plugin SQL, {} awaiting egress; retention does not delete "
                            + "unprocessed queue work (issue #212)",
                    heldBack.segments, siteId, heldBack.pendingPluginSql, heldBack.pendingEgress);
        }
        if (!prunedKeys.isEmpty()) {
            log.info("Pruned {} changelog segment(s) below checkpoint@{} for site {} (audit window {})",
                    prunedKeys.size(), checkpointSeq, siteId, auditWindowSegments);
        }
        return prunedKeys.size();
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
     * @param siteId     the site being pruned, for the log line
     * @param prunedKeys keys whose row delete reported success; may be empty
     */
    private void deletePrunedObjects(UUID siteId, List<String> prunedKeys) {
        if (prunedKeys.isEmpty()) {
            return;
        }
        try {
            S3FileStorageService.DeleteObjectsResult result = objectDeleter.deleteObjects(prunedKeys);
            if (!result.errors().isEmpty()) {
                log.warn("Pruned {} changelog segment row(s) for site {} but {} object delete(s) "
                                + "failed — the objects are unreferenced and the S3 orphan sweep "
                                + "reclaims them (issue #158)",
                        prunedKeys.size(), siteId, result.errors().size());
            }
        } catch (RuntimeException e) {
            log.warn("Pruned {} changelog segment row(s) for site {} but the object delete "
                            + "failed mid-way — the undeleted objects are unreferenced and the "
                            + "S3 orphan sweep reclaims them (issue #158)",
                    prunedKeys.size(), siteId, e);
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

    /** The hold-back census of one pass — the same counting for the view and the re-read (R2-9). */
    private static final class HeldBackTally {

        private int segments;
        private int pendingPluginSql;
        private int pendingEgress;

        /** @return {@code true} when the segment was pending (and has now been counted) */
        private boolean countIfPending(boolean pluginSqlPending, boolean egressPending) {
            if (!pluginSqlPending && !egressPending) {
                return false;
            }
            segments++;
            if (pluginSqlPending) {
                pendingPluginSql++;
            }
            if (egressPending) {
                pendingEgress++;
            }
            return true;
        }
    }
}
