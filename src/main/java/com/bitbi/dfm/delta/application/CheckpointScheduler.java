package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Periodically materializes checkpoints for sites with changelog data (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class CheckpointScheduler {

    private static final Logger log = LoggerFactory.getLogger(CheckpointScheduler.class);

    private final CheckpointService checkpointService;
    private final ChangelogSegmentRepository segmentRepository;

    public CheckpointScheduler(CheckpointService checkpointService,
                               ChangelogSegmentRepository segmentRepository) {
        this.checkpointService = checkpointService;
        this.segmentRepository = segmentRepository;
    }

    @Scheduled(cron = "${delta.checkpoint.cron:0 0 2 * * *}")
    public void buildCheckpoints() {
        for (UUID siteId : segmentRepository.findDistinctSiteIds()) {
            try {
                checkpointService.buildCheckpoint(siteId);
            } catch (RuntimeException e) {
                log.warn("Checkpoint build failed for site {}: {}", siteId, e.getMessage());
            }
        }
    }
}
