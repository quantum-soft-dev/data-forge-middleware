package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Periodically materializes checkpoints for sites with changelog data (Delta Client v2 — 022).
 *
 * <p>An in-JVM lock prevents a slow run from overlapping the next cron tick. This guards a single
 * instance only; in a multi-instance deployment run this scheduler on one instance (or add a
 * distributed lock such as ShedLock) so two instances do not build the same site concurrently.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class CheckpointScheduler {

    private static final Logger log = LoggerFactory.getLogger(CheckpointScheduler.class);

    private final CheckpointService checkpointService;
    private final ChangelogRetentionService retentionService;
    private final ChangelogSegmentRepository segmentRepository;
    private final CheckpointRepository checkpointRepository;
    private final ReentrantLock buildLock = new ReentrantLock();

    public CheckpointScheduler(CheckpointService checkpointService,
                               ChangelogRetentionService retentionService,
                               ChangelogSegmentRepository segmentRepository,
                               CheckpointRepository checkpointRepository) {
        this.checkpointService = checkpointService;
        this.retentionService = retentionService;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
    }

    @Scheduled(cron = "${delta.checkpoint.cron:0 0 2 * * *}")
    public void buildCheckpoints() {
        if (!buildLock.tryLock()) {
            log.info("Checkpoint build already in progress; skipping this tick");
            return;
        }
        try {
            for (UUID siteId : sitesToVisit()) {
                try {
                    checkpointService.buildCheckpoint(siteId);
                    retentionService.prune(siteId);
                } catch (RuntimeException e) {
                    log.warn("Checkpoint build/retention failed for site {}: {}", siteId, e.getMessage());
                }
            }
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * The sites this tick has work for: those with changelog segments, plus those owed a
     * rematerialize (issue #137).
     *
     * <p>Segments alone are not the work list. Once retention has pruned a site's changelog to
     * nothing — {@code audit-window-segments=0}, or a table detached for longer than the window —
     * no segment row names it, yet a table whose snapshot is missing can still be rebuilt from the
     * checkpoint frame ({@code CheckpointService}, issue #128). Only sites with an actually
     * unmaterialized checkpoint are added: having checkpoints is not a reason to visit, and a build
     * discarded because the site's baseline epoch moved is a normal outcome, not a failure.</p>
     *
     * <p>Segment sites come first and the set de-duplicates, so a site on both lists is built once,
     * in the position it had when segments were the only source.</p>
     */
    private Set<UUID> sitesToVisit() {
        Set<UUID> siteIds = new LinkedHashSet<>(segmentRepository.findDistinctSiteIds());
        siteIds.addAll(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints());
        return siteIds;
    }
}
