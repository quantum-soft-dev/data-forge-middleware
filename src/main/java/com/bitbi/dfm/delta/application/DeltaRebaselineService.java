package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resets a site's durable delta state for a FULL_SNAPSHOT re-baseline (Delta Client v2 — 022).
 *
 * <p>A re-baseline means "discard everything; the incoming snapshot is the complete new state". The
 * old changelog segments and checkpoints are removed and the watermarks are reset, so the next
 * checkpoint folds <em>only</em> the snapshot — rows that disappeared since the previous baseline do
 * not survive, and tables absent from the snapshot stop being served. Without this the fold would
 * merge the snapshot on top of the old state (CR §8.D).</p>
 *
 * <p>Old S3 checkpoint snapshot/frame objects are left as harmless orphans: serving is driven by the
 * checkpoint rows (removed here) and the next build writes fresh seq-keyed objects.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaRebaselineService {

    private static final Logger log = LoggerFactory.getLogger(DeltaRebaselineService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final S3ChangelogSegmentStorage segmentStorage;
    private final CheckpointRepository checkpointRepository;
    private final SiteSyncStateRepository syncStateRepository;

    public DeltaRebaselineService(ChangelogSegmentRepository segmentRepository,
                                  S3ChangelogSegmentStorage segmentStorage,
                                  CheckpointRepository checkpointRepository,
                                  SiteSyncStateRepository syncStateRepository) {
        this.segmentRepository = segmentRepository;
        this.segmentStorage = segmentStorage;
        this.checkpointRepository = checkpointRepository;
        this.syncStateRepository = syncStateRepository;
    }

    /**
     * Wipe prior changelog segments and checkpoints for a site and reset its watermark so an incoming
     * FULL_SNAPSHOT starting at {@code firstSeq} becomes the new baseline.
     *
     * @param siteId   site identifier
     * @param firstSeq first sequence of the snapshot session
     */
    @Transactional
    public void reset(UUID siteId, long firstSeq) {
        // 033: findBySiteIdOrderByFirstSeq returns committed segments only. A large re-baseline seals
        // its own segments as provisional before SessionEnd gets here, so they are excluded by
        // construction — this deletes the baseline being replaced, never the snapshot replacing it.
        // Delete the metadata rows in-transaction, but defer the S3 object deletes to afterCommit:
        // if the enclosing commit (the new snapshot segment persist + watermark advance) rolls back,
        // the old rows are restored and their S3 objects must still exist. Deferring keeps the old
        // baseline fully intact until the new one is durably committed (review r4).
        List<String> s3Keys = new ArrayList<>();
        int segments = 0;
        for (ChangelogSegment segment : segmentRepository.findBySiteIdOrderByFirstSeq(siteId)) {
            s3Keys.add(segment.getS3Key());
            segmentRepository.deleteById(segment.getId());
            segments++;
        }

        int checkpoints = 0;
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            checkpointRepository.deleteById(checkpoint.getId());
            checkpoints++;
        }

        SiteSyncState state = syncStateRepository.findBySiteId(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));
        state.resetForRebaseline(firstSeq - 1);
        syncStateRepository.save(state);

        deleteOldObjectsAfterCommit(s3Keys);

        log.info("Re-baselined site {}: cleared {} segment(s), {} checkpoint(s); watermark reset to {}",
                siteId, segments, checkpoints, firstSeq - 1);
    }

    /**
     * Collect the invisible remains of a re-baseline that never reached {@code SessionEnd} (033).
     *
     * <p>Scoped to the batch that wrote them, never to the site. A site-wide sweep would let one
     * session destroy another's in-flight snapshot the moment the one-active-batch rule has a gap —
     * an aborting stream and a legitimately-started replacement can overlap — and the loss would be
     * silent, because provisional segments are invisible to every reader.</p>
     *
     * <p>Deliberately narrower than {@link #reset}: no checkpoint or watermark change, because
     * nothing these segments contain was ever published.</p>
     *
     * @param batchId the session's batch
     * @return number of orphaned provisional segments deleted
     */
    @Transactional
    public int deleteProvisionalByBatch(UUID batchId) {
        return purge(segmentRepository.findProvisionalByBatchId(batchId),
                "batch " + batchId);
    }

    /**
     * Sweep provisional segments whose owning batch is no longer running (033).
     *
     * <p>The batch-scoped deletes above only fire when the server is still alive to run them. A pod
     * that is OOM-killed or rescheduled mid-snapshot leaves rows behind that no reader can see, that
     * retention cannot reach (it enumerates published segments only), and that no session will
     * revisit. A staged session keeps its batch {@code IN_PROGRESS} on purpose, so "batch not
     * running" is exactly the set that can never be resumed.</p>
     *
     * @param limit maximum segments to collect in one pass
     * @return number of orphaned provisional segments deleted
     */
    @Transactional
    public int sweepOrphanedProvisional(int limit) {
        return purge(segmentRepository.findProvisionalWithoutRunningBatch(limit), "dead batches");
    }

    /** Delete the rows in-transaction and their S3 objects after commit; returns the count. */
    private int purge(List<ChangelogSegment> segments, String scope) {
        List<String> s3Keys = new ArrayList<>();
        for (ChangelogSegment segment : segments) {
            s3Keys.add(segment.getS3Key());
            segmentRepository.deleteById(segment.getId());
        }
        if (s3Keys.isEmpty()) {
            return 0;
        }
        deleteOldObjectsAfterCommit(s3Keys);
        log.info("Collected {} orphaned provisional segment(s) ({})", s3Keys.size(), scope);
        return s3Keys.size();
    }

    /**
     * Delete the old segments' S3 objects only once the surrounding transaction commits (so a
     * rollback leaves them intact); outside a transaction (unit tests) the delete is immediate. Old
     * objects that outlive a crash between commit and delete are harmless orphans (serving is driven
     * by the rows, already gone).
     */
    private void deleteOldObjectsAfterCommit(List<String> s3Keys) {
        if (s3Keys.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    s3Keys.forEach(segmentStorage::delete);
                }
            });
        } else {
            s3Keys.forEach(segmentStorage::delete);
        }
    }
}
