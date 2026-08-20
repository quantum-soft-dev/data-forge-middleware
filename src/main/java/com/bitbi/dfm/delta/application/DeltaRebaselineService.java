package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
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
 * checkpoint rows (removed here) and the next build writes fresh seq-keyed objects. That the old
 * <em>frame</em> also survives is only harmless because the checkpoint pointer cannot come back —
 * {@code CheckpointEpochGuard} refuses a build that overlapped this reset, keyed on the baseline
 * epoch bumped here (issue #142). Without that, a restored pointer would name the discarded
 * baseline's frame and the next fold would seed from it.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaRebaselineService {

    private static final Logger log = LoggerFactory.getLogger(DeltaRebaselineService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final S3FileStorageService objectDeleter;
    private final CheckpointRepository checkpointRepository;
    private final SiteSyncStateRepository syncStateRepository;

    public DeltaRebaselineService(ChangelogSegmentRepository segmentRepository,
                                  S3FileStorageService objectDeleter,
                                  CheckpointRepository checkpointRepository,
                                  SiteSyncStateRepository syncStateRepository) {
        this.segmentRepository = segmentRepository;
        this.objectDeleter = objectDeleter;
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
        // Per-site mutex first, before anything is destroyed (issue #142). It is the same row lock a
        // history wipe holds for its whole transaction and the one CheckpointEpochGuard blocks on,
        // so a concurrent checkpoint build has only two possible orderings: its writes commit before
        // this reset begins (and the deletes below take them with the rest of the old baseline), or
        // it waits here and is then refused because resetForRebaseline moved the baseline epoch.
        // Loading the row last, as a plain read, left a window between the checkpoint deletes and
        // the epoch bump in which a guarded write could land and outlive the reset.
        //
        // A site that has never synced has no row to lock, so the mutex is vacuous there (as it is
        // in DeltaSiteWipeService, which documents the same caveat): the row is created here, and
        // two operations racing on a fresh site collide on the primary key instead. There is also no
        // checkpoint history for a build to resurrect on such a site.
        //
        // The reset runs first inside the ingestion commit transaction, so this lock is held for the
        // rest of it. Everything that follows is a statement: the tail segment's object is uploaded
        // before that transaction opens (issue #147), so the hold no longer spans a network call.
        SiteSyncState state = syncStateRepository.findBySiteIdForUpdate(siteId)
                .orElseGet(() -> SiteSyncState.initial(siteId));

        // 033: both statements are committed-only. A large re-baseline seals its own segments as
        // provisional before SessionEnd gets here, so they are excluded by construction — this
        // deletes the baseline being replaced, never the snapshot replacing it.
        // Delete the metadata rows in-transaction, but defer the S3 object deletes to afterCommit:
        // if the enclosing commit (the new snapshot segment persist + watermark advance) rolls back,
        // the old rows are restored and their S3 objects must still exist. Deferring keeps the old
        // baseline fully intact until the new one is durably committed (review r4).
        // One projection query and one bulk DELETE, not a row-by-row loop (issue #212 review):
        // the backlog this discards is unbounded — held-back pending segments included — and it
        // runs inside the SessionEnd commit under the site_sync_state row lock taken above.
        // The DELETE takes exactly the ids whose keys were collected (round 2, R2-8): a blanket
        // site-wide DELETE could take a row committed between the two statements, whose key was
        // never collected — an orphan object until the #158 sweep.
        List<ChangelogSegmentRepository.CommittedSegmentRef> refs =
                segmentRepository.findCommittedRefsBySiteId(siteId);
        List<String> s3Keys = refs.stream()
                .map(ChangelogSegmentRepository.CommittedSegmentRef::getS3Key).toList();
        int segments = refs.isEmpty() ? 0 : segmentRepository.deleteByIdIn(
                refs.stream().map(ChangelogSegmentRepository.CommittedSegmentRef::getId).toList());

        int checkpoints = 0;
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            checkpointRepository.deleteById(checkpoint.getId());
            checkpoints++;
        }

        state.resetForRebaseline(firstSeq - 1);
        syncStateRepository.save(state);

        deleteOldObjectsAfterCommit(s3Keys);

        log.info("Re-baselined site {}: cleared {} segment(s), {} checkpoint(s); watermark reset to "
                        + "{}, baseline epoch now {}",
                siteId, segments, checkpoints, firstSeq - 1, state.getBaselineEpoch());
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
                    deleteObjectsBestEffort(s3Keys);
                }
            });
        } else {
            deleteObjectsBestEffort(s3Keys);
        }
    }

    /**
     * Batched 1000-key {@code DeleteObjects} instead of one round trip per key (issue #212 review
     * round 2): the backlog a re-baseline discards is unbounded, and this runs on the SessionEnd
     * commit thread. Best-effort — the rows are gone, so anything left behind is the unreferenced
     * litter the #158 sweep reclaims.
     */
    private void deleteObjectsBestEffort(List<String> s3Keys) {
        try {
            S3FileStorageService.DeleteObjectsResult result = objectDeleter.deleteObjects(s3Keys);
            if (!result.errors().isEmpty()) {
                log.warn("Discarded {} old segment row(s) but {} object delete(s) failed — the "
                                + "objects are unreferenced and the S3 orphan sweep reclaims them "
                                + "(issue #158)",
                        s3Keys.size(), result.errors().size());
            }
        } catch (RuntimeException e) {
            log.warn("Discarded {} old segment row(s) but the object delete failed mid-way — the "
                            + "undeleted objects are unreferenced and the S3 orphan sweep reclaims "
                            + "them (issue #158)",
                    s3Keys.size(), e);
        }
    }
}
