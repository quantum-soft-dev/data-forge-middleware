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
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ChangelogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ChangelogRetentionService.class);

    private final ChangelogSegmentRepository segmentRepository;
    private final S3ChangelogSegmentStorage segmentStorage;
    private final DeltaSyncStateService syncStateService;
    private final int auditWindowSegments;

    public ChangelogRetentionService(ChangelogSegmentRepository segmentRepository,
                                     S3ChangelogSegmentStorage segmentStorage,
                                     DeltaSyncStateService syncStateService,
                                     @Value("${delta.retention.audit-window-segments:20}") int auditWindowSegments) {
        this.segmentRepository = segmentRepository;
        this.segmentStorage = segmentStorage;
        this.syncStateService = syncStateService;
        this.auditWindowSegments = Math.max(0, auditWindowSegments);
    }

    /**
     * Prune below-checkpoint segments for a site, keeping the audit window.
     *
     * @param siteId site identifier
     * @return number of segments pruned
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
        for (int i = 0; i < pruneCount; i++) {
            ChangelogSegment segment = belowCheckpoint.get(i);
            segmentStorage.delete(segment.getS3Key());
            segmentRepository.deleteById(segment.getId());
        }

        if (pruneCount > 0) {
            log.info("Pruned {} changelog segment(s) below checkpoint@{} for site {} (audit window {})",
                    pruneCount, checkpointSeq, siteId, auditWindowSegments);
        }
        return pruneCount;
    }
}
