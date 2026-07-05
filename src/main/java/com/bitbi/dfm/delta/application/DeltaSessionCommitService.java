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

    public DeltaSessionCommitService(ChangelogSegmentService changelogSegmentService,
                                     DeltaSyncStateService syncStateService,
                                     BatchLifecycleService batchLifecycleService,
                                     DeltaEgressWorker egressWorker) {
        this.changelogSegmentService = changelogSegmentService;
        this.syncStateService = syncStateService;
        this.batchLifecycleService = batchLifecycleService;
        this.egressWorker = egressWorker;
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
    @Transactional
    public String commit(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                         List<ChangeRecord> records) {
        String segmentKey = "";
        // An empty session persists no segment: a degenerate segment at first_seq=watermark+1 would
        // not advance the watermark and would then collide on UNIQUE(site_id, first_seq).
        if (!records.isEmpty()) {
            ChangelogSegment segment = changelogSegmentService.persist(siteId, batchId, mode, firstSeq, records);
            segmentKey = segment.getS3Key();
            wakeEgressAfterCommit();
        }
        syncStateService.advanceWatermark(siteId, committedSeq);
        batchLifecycleService.completeBatch(batchId);
        return segmentKey;
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
