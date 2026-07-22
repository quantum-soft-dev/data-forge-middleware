package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final ReentrantLock buildLock = new ReentrantLock();

    public CheckpointScheduler(CheckpointService checkpointService,
                               ChangelogRetentionService retentionService,
                               ChangelogSegmentRepository segmentRepository) {
        this.checkpointService = checkpointService;
        this.retentionService = retentionService;
        this.segmentRepository = segmentRepository;
    }

    @Scheduled(cron = "${delta.checkpoint.cron:0 0 2 * * *}")
    public void buildCheckpoints() {
        if (!buildLock.tryLock()) {
            log.info("Checkpoint build already in progress; skipping this tick");
            return;
        }
        try {
            for (UUID siteId : segmentRepository.findDistinctSiteIds()) {
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
}
