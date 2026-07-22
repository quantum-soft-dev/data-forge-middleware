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
