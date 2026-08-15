package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * The database half of an ingestion commit (Delta Client v2 — 022; split out in issue #147).
 *
 * <p>Everything here runs in one transaction, and everything here is a statement: the segment's
 * object is already in storage by the time any of these methods is entered, so no lock this
 * transaction takes is ever held across a network upload. That matters most on the FULL_SNAPSHOT
 * path — {@link DeltaRebaselineService#reset} takes the {@code site_sync_state} row lock as its
 * first statement (issue #142) and holds it until commit, and the tail segment's {@code PutObject}
 * used to sit inside that hold. It also restores the invariant the rest of this subsystem states
 * everywhere else ({@code CheckpointEpochGuard}: "No S3 traffic happens inside the lock").</p>
 *
 * <p>Separate bean rather than a method on {@link DeltaSessionCommitService} because a
 * {@code @Transactional} method invoked on {@code this} is not proxied: the orchestrator has to
 * cross a bean boundary for the transaction to start where it does.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSessionCommitTransaction {

    private final ChangelogSegmentService changelogSegmentService;
    private final DeltaSyncStateService syncStateService;
    private final BatchLifecycleService batchLifecycleService;
    private final DeltaEgressWorker egressWorker;
    private final DeltaRebaselineService rebaselineService;

    public DeltaSessionCommitTransaction(ChangelogSegmentService changelogSegmentService,
                                         DeltaSyncStateService syncStateService,
                                         BatchLifecycleService batchLifecycleService,
                                         DeltaEgressWorker egressWorker,
                                         DeltaRebaselineService rebaselineService) {
        this.changelogSegmentService = changelogSegmentService;
        this.syncStateService = syncStateService;
        this.batchLifecycleService = batchLifecycleService;
        this.egressWorker = egressWorker;
        this.rebaselineService = rebaselineService;
    }

    /**
     * Record the (already uploaded) tail segment, advance the watermark and complete the batch —
     * optionally discarding the previous baseline first.
     *
     * @param siteId          site identifier
     * @param batchId         batch (session) identifier
     * @param committedSeq    highest sequence now durably applied
     * @param prepared        the uploaded tail segment, or {@code null} for an empty session
     * @param rebaseline      whether to reset the prior baseline before recording (FULL_SNAPSHOT)
     * @param sessionFirstSeq first sequence of the whole session (033: differs from the tail's once
     *                        a re-baseline sealed mid-stream)
     * @return the recorded segment's S3 key, or {@code ""} for an empty session
     */
    @Transactional
    public String commit(UUID siteId, UUID batchId, long committedSeq,
                         PreparedSegment prepared, boolean rebaseline, long sessionFirstSeq) {
        if (rebaseline) {
            // Wipe the old baseline first (in this transaction) — it deletes all prior committed
            // segments, so it must run before the tail segment row is written below. Provisional
            // segments sealed earlier in this session are excluded by construction (033/T03).
            rebaselineService.reset(siteId, sessionFirstSeq);
        }
        String segmentKey = "";
        // An empty session records no segment: a degenerate segment at first_seq=watermark+1 would
        // not advance the watermark and would then collide on UNIQUE(site_id, first_seq).
        if (prepared != null) {
            segmentKey = changelogSegmentService.persistPrepared(prepared).getS3Key();
            wakeEgressAfterCommit();
        }
        if (rebaseline) {
            // Publish the segments sealed earlier in this session, after the old baseline is gone and
            // before the watermark moves: readers switch from the whole old baseline to the whole new
            // one in one transaction. A no-op for a snapshot small enough never to have sealed.
            if (changelogSegmentService.publishProvisional(batchId) > 0) {
                wakeEgressAfterCommit();
            }
        }
        syncStateService.advanceWatermark(siteId, committedSeq);
        batchLifecycleService.completeBatch(batchId);
        return segmentKey;
    }

    /**
     * Record a mid-session seal and advance the watermark, leaving the batch open (029).
     *
     * @return the recorded segment's S3 key
     */
    @Transactional
    public String commitSegment(UUID siteId, long committedSeq, PreparedSegment prepared) {
        String segmentKey = changelogSegmentService.persistPrepared(prepared).getS3Key();
        wakeEgressAfterCommit();
        syncStateService.advanceWatermark(siteId, committedSeq);
        return segmentKey;
    }

    /**
     * Record a mid-snapshot seal of a re-baseline as a provisional segment (033) — no watermark
     * move, no egress wake, no batch completion.
     *
     * @return the recorded segment's S3 key
     */
    @Transactional
    public String commitProvisionalSegment(PreparedSegment prepared) {
        return changelogSegmentService.persistPreparedProvisional(prepared).getS3Key();
    }

    /**
     * Move a resumed session's provisional segments onto the batch it now runs under (033 review).
     *
     * @return number of segments moved
     */
    @Transactional
    public int reassignProvisionalSegments(UUID fromBatchId, UUID toBatchId) {
        return changelogSegmentService.reassignProvisionalBatch(fromBatchId, toBatchId);
    }

    /**
     * Wake the delta egress worker once this transaction commits (T8.4) — the worker must see the
     * committed segment row. Outside a transaction (unit tests) the wake is immediate.
     */
    private void wakeEgressAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    egressWorker.wake();
                }
            });
        } else {
            egressWorker.wake();
        }
    }
}
