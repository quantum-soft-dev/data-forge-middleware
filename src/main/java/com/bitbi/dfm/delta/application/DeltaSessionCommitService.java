package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Atomically commits one ingestion segment (Delta Client v2 — 022).
 *
 * <p>Persisting the changelog segment, advancing the site watermark, and completing the batch were
 * three independent transactions; a failure between them could leave the watermark ahead of a batch
 * still {@code IN_PROGRESS}, wedging the site. This service runs them in a single transaction so they
 * either all commit or all roll back.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSessionCommitService {

    private final ChangelogSegmentService changelogSegmentService;
    private final DeltaSyncStateService syncStateService;
    private final BatchLifecycleService batchLifecycleService;
    private final DeltaEgressWorker egressWorker;
    private final DeltaRebaselineService rebaselineService;

    public DeltaSessionCommitService(ChangelogSegmentService changelogSegmentService,
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
     * Persist the segment (when non-empty), advance the watermark, and complete the batch as one
     * transaction.
     *
     * @param siteId       site identifier
     * @param batchId      batch (session) identifier
     * @param mode         session mode
     * @param firstSeq     first sequence of the segment
     * @param committedSeq highest sequence now durably applied
     * @param records      accepted change records (may be empty)
     * @return the persisted segment's S3 key, or {@code ""} for an empty session (no segment written)
     */
    public String commit(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                         List<ChangeRecord> records) {
        return commit(siteId, batchId, mode, firstSeq, committedSeq, records, false);
    }

    /**
     * As {@link #commit(UUID, UUID, String, long, long, List)}, but when {@code rebaseline} is true
     * the prior baseline (old segments + checkpoints) is wiped <em>in the same transaction</em>,
     * before the new snapshot segment is persisted. This is what makes a FULL_SNAPSHOT atomic: the
     * old baseline survives until the new one durably commits, so a snapshot that drops mid-stream
     * leaves the old baseline intact (review r4).
     *
     * @param rebaseline whether to reset the prior baseline before persisting (FULL_SNAPSHOT)
     */
    public String commit(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                         List<ChangeRecord> records, boolean rebaseline) {
        return commit(siteId, batchId, mode, firstSeq, committedSeq, records, rebaseline, firstSeq);
    }

    /**
     * As above, but {@code sessionFirstSeq} is the first sequence of the whole session rather than of
     * the segment being persisted. The two differ once a re-baseline seals mid-stream (033): the
     * final call carries only the tail, while the baseline reset must be expressed in terms of where
     * the snapshot started.
     *
     * @param sessionFirstSeq first sequence of the session (== {@code firstSeq} when it never sealed)
     */
    @Transactional
    public String commit(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                         List<ChangeRecord> records, boolean rebaseline, long sessionFirstSeq) {
        if (rebaseline) {
            // Wipe the old baseline first (in this transaction) — it deletes all prior committed
            // segments, so it must run before the tail segment is persisted below. Provisional
            // segments sealed earlier in this session are excluded by construction (033/T03).
            rebaselineService.reset(siteId, sessionFirstSeq);
        }
        String segmentKey = "";
        // An empty session persists no segment: a degenerate segment at first_seq=watermark+1 would
        // not advance the watermark and would then collide on UNIQUE(site_id, first_seq).
        if (!records.isEmpty()) {
            ChangelogSegment segment = changelogSegmentService.persist(siteId, batchId, mode, firstSeq, records);
            segmentKey = segment.getS3Key();
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
     * Seal a mid-snapshot segment of a re-baseline (033): durable, but {@code provisional} — hidden
     * from the checkpoint fold and both work queues, and deliberately <em>not</em> advancing the
     * watermark.
     *
     * <p>This is what lets a snapshot larger than {@code delta.ingestion.max-session-records} stream
     * at all: the buffer is drained on each seal instead of growing to the whole dataset. Holding the
     * watermark back keeps {@code GetSyncState} reporting the pre-snapshot position, so a client that
     * drops mid-snapshot restarts a clean snapshot rather than resuming into a half-replaced
     * baseline — and the old baseline keeps serving until {@code SessionEnd} publishes the new one.</p>
     *
     * @param siteId   site identifier
     * @param batchId  the session's batch (stays open)
     * @param mode     session mode ({@code FULL_SNAPSHOT})
     * @param firstSeq first sequence of this segment
     * @param records  accepted change records
     * @return the persisted segment's S3 key, or {@code ""} when {@code records} is empty (no-op)
     */
    @Transactional
    public String commitProvisionalSegment(UUID siteId, UUID batchId, String mode, long firstSeq,
                                           List<ChangeRecord> records) {
        if (records.isEmpty()) {
            return "";
        }
        return changelogSegmentService.persistProvisional(siteId, batchId, mode, firstSeq, records).getS3Key();
    }

    /**
     * Persist a mid-session segment and advance the watermark <em>without touching the batch</em>
     * (029: batch = one session; a continuous-mode seal is a durability event, not a batch
     * boundary). The session's batch stays {@code IN_PROGRESS} until {@code SessionEnd} runs the
     * completing {@link #commit(UUID, UUID, String, long, long, List)}.
     *
     * @param siteId       site identifier
     * @param batchId      the session's batch (stays open)
     * @param mode         session mode
     * @param firstSeq     first sequence of the segment
     * @param committedSeq highest sequence now durably applied
     * @param records      accepted change records
     * @return the persisted segment's S3 key, or {@code ""} when {@code records} is empty (no-op)
     */
    @Transactional
    public String commitSegment(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                                List<ChangeRecord> records) {
        if (records.isEmpty()) {
            return "";
        }
        ChangelogSegment segment = changelogSegmentService.persist(siteId, batchId, mode, firstSeq, records);
        wakeEgressAfterCommit();
        syncStateService.advanceWatermark(siteId, committedSeq);
        return segment.getS3Key();
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
