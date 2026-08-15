package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Atomically commits one ingestion segment (Delta Client v2 — 022).
 *
 * <p>Persisting the changelog segment, advancing the site watermark, and completing the batch were
 * three independent transactions; a failure between them could leave the watermark ahead of a batch
 * still {@code IN_PROGRESS}, wedging the site. {@link DeltaSessionCommitTransaction} runs them in a
 * single transaction so they either all commit or all roll back.</p>
 *
 * <p>This class opens <em>no</em> transaction of its own (issue #147). It is the ordering half of
 * the commit: the segment's bytes go to object storage first, with nothing open, and only then is
 * the transaction entered — so the row locks it takes, including the {@code site_sync_state} row a
 * FULL_SNAPSHOT re-baseline locks before it deletes anything (issue #142), are never held across a
 * network upload. An upload whose transaction then rolls back leaves an unreachable orphan object,
 * exactly as it did when the upload sat inside the transaction: nothing deleted it on rollback
 * then either.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSessionCommitService {

    private final ChangelogSegmentService changelogSegmentService;
    private final DeltaSessionCommitTransaction transaction;

    public DeltaSessionCommitService(ChangelogSegmentService changelogSegmentService,
                                     DeltaSessionCommitTransaction transaction) {
        this.changelogSegmentService = changelogSegmentService;
        this.transaction = transaction;
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
    public String commit(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                         List<ChangeRecord> records, boolean rebaseline, long sessionFirstSeq) {
        // Upload first, transaction second (issue #147). An empty session uploads nothing: a
        // degenerate segment at first_seq=watermark+1 would not advance the watermark and would then
        // collide on UNIQUE(site_id, first_seq).
        PreparedSegment prepared = records.isEmpty()
                ? null
                : changelogSegmentService.prepare(siteId, batchId, mode, firstSeq, records);
        return transaction.commit(siteId, batchId, committedSeq, prepared, rebaseline, sessionFirstSeq);
    }

    /**
     * Move a resumed session's provisional segments onto the batch it now runs under (033 review).
     *
     * <p>A resume whose original batch had already been reaped continues under a replacement batch.
     * Publication is keyed on the batch, and {@link DeltaRebaselineService#reset} cannot see
     * provisional rows, so segments left under the old batch would be neither published nor deleted:
     * the snapshot would commit a baseline missing everything it streamed before the drop, with the
     * watermark advanced and the client told it succeeded. Reconciliation does not catch it — the
     * totals travel with the staged session and still match.</p>
     *
     * @param fromBatchId the reaped batch the segments were sealed under
     * @param toBatchId   the replacement batch
     * @return number of segments moved
     */
    public int reassignProvisionalSegments(UUID fromBatchId, UUID toBatchId) {
        if (fromBatchId == null || fromBatchId.equals(toBatchId)) {
            return 0;
        }
        return transaction.reassignProvisionalSegments(fromBatchId, toBatchId);
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
    public String commitProvisionalSegment(UUID siteId, UUID batchId, String mode, long firstSeq,
                                           List<ChangeRecord> records) {
        if (records.isEmpty()) {
            return "";
        }
        return transaction.commitProvisionalSegment(
                changelogSegmentService.prepare(siteId, batchId, mode, firstSeq, records));
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
    public String commitSegment(UUID siteId, UUID batchId, String mode, long firstSeq, long committedSeq,
                                List<ChangeRecord> records) {
        if (records.isEmpty()) {
            return "";
        }
        return transaction.commitSegment(siteId, committedSeq,
                changelogSegmentService.prepare(siteId, batchId, mode, firstSeq, records));
    }
}
