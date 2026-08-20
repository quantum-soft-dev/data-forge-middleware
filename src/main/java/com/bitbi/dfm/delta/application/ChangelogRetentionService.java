package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * one WARN per site per pass, so a stuck backlog is visible before it is large. The predicate
 * cannot pin a segment forever by design elsewhere: every segment is egressed regardless of plugin
 * state (tables without a schema are skipped but the segment is still marked), the delta-SQL queue
 * stamps {@code plugin_sql_at} without generating for inactive activations and for
 * {@code FULL_SNAPSHOT} baselines, and provisional parking sets both markers to a sentinel
 * ({@code ChangelogSegment.markProvisional}). Held-back segments still count toward the audit
 * window — the window keeps its meaning ("the most recent N below-checkpoint segments"), and the
 * hold-back retains segments on top of it rather than re-shaping it.</p>
 *
 * <p><b>Deliberately no age or count bound on the hold-back.</b> The main permanent-stall scenario
 * — a mistyped {@code plugin.sql-generation.heap-threshold-percent} making every generation refuse
 * forever — is closed at source by #185's fail-fast validation, and a deterministic poison batch is
 * already loud through {@code sql.generation.errors} and the #181 audit entries. If storage pinning
 * ever becomes real, a bound is its own decision with its own ticket; the starting point here is
 * "stop losing work silently". The only thing that ends a hold-back besides the queue draining is
 * an operator deleting the segment.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ChangelogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ChangelogRetentionService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final S3ChangelogSegmentStorage segmentStorage;
    private final DeltaSyncStateService syncStateService;
    private final DeltaMetrics metrics;
    private final int auditWindowSegments;

    public ChangelogRetentionService(ChangelogSegmentRepository segmentRepository,
                                     S3ChangelogSegmentStorage segmentStorage,
                                     DeltaSyncStateService syncStateService,
                                     DeltaMetrics metrics,
                                     @Value("${delta.retention.audit-window-segments:20}") int auditWindowSegments) {
        this.segmentRepository = segmentRepository;
        this.segmentStorage = segmentStorage;
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
     */
    @Transactional
    public int prune(UUID siteId) {
        long checkpointSeq = syncStateService.getSyncState(siteId).lastCheckpointSeq();
        if (checkpointSeq <= 0) {
            return 0;
        }

        // Below-checkpoint segments, oldest first (findBySiteIdOrderByFirstSeq is ordered by first_seq).
        List<ChangelogSegment> belowCheckpoint = segmentRepository.findBySiteIdOrderByFirstSeq(siteId).stream()
                .filter(segment -> segment.getLastSeq() <= checkpointSeq)
                .toList();

        int pruneCount = Math.max(0, belowCheckpoint.size() - auditWindowSegments);
        int pruned = 0;
        int heldBack = 0;
        int pendingPluginSql = 0;
        int pendingEgress = 0;
        for (int i = 0; i < pruneCount; i++) {
            ChangelogSegment segment = belowCheckpoint.get(i);
            boolean pluginSqlPending = segment.getPluginSqlAt() == null;
            boolean egressPending = segment.getEgressAt() == null;
            if (pluginSqlPending || egressPending) {
                heldBack++;
                if (pluginSqlPending) {
                    pendingPluginSql++;
                }
                if (egressPending) {
                    pendingEgress++;
                }
                continue;
            }
            segmentStorage.delete(segment.getS3Key());
            segmentRepository.deleteById(segment.getId());
            pruned++;
        }

        if (heldBack > 0) {
            metrics.retentionSegmentsHeldBack(DeltaMetrics.RETENTION_PENDING_PLUGIN_SQL, pendingPluginSql);
            metrics.retentionSegmentsHeldBack(DeltaMetrics.RETENTION_PENDING_EGRESS, pendingEgress);
            log.warn("Held back {} below-checkpoint segment(s) with pending work for site {} — "
                            + "{} awaiting plugin SQL, {} awaiting egress; retention does not delete "
                            + "unprocessed work (issue #212)",
                    heldBack, siteId, pendingPluginSql, pendingEgress);
        }
        if (pruned > 0) {
            log.info("Pruned {} changelog segment(s) below checkpoint@{} for site {} (audit window {})",
                    pruned, checkpointSeq, siteId, auditWindowSegments);
        }
        return pruned;
    }
}
