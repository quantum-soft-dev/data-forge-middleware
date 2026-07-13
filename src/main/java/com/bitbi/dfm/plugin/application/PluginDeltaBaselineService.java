package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.site.domain.Site;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Captures per-table SQL baselines for Delta v2 sites (026-bitbi-delta-sql).
 *
 * <p>At plugin activation/reinit the site's current {@link Checkpoint} seqs are frozen into
 * {@code plugin_delta_baselines}: the client downloads exactly those checkpoint CSVs via
 * {@code /files}, so SQL must start strictly after them ({@code seq > baseline_seq(table)}).
 * A table without a checkpoint gets no row (baseline 0 — full retained history streams).</p>
 *
 * <p>Reinit additionally re-enqueues the site's segments ({@code plugin_sql_at = NULL}) so the
 * checkpoint-lag gap regenerates under the new baselines, and wakes the sweep worker after the
 * reinit transaction commits.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class PluginDeltaBaselineService {

    private static final Logger log = LoggerFactory.getLogger(PluginDeltaBaselineService.class);

    private final com.bitbi.dfm.site.domain.SiteRepository siteRepository;
    private final CheckpointRepository checkpointRepository;
    private final PluginDeltaBaselineRepository baselineRepository;
    private final ChangelogSegmentRepository segmentRepository;
    private final DeltaSqlSweepWorker sweepWorker;

    public PluginDeltaBaselineService(com.bitbi.dfm.site.domain.SiteRepository siteRepository,
                                      CheckpointRepository checkpointRepository,
                                      PluginDeltaBaselineRepository baselineRepository,
                                      ChangelogSegmentRepository segmentRepository,
                                      DeltaSqlSweepWorker sweepWorker) {
        this.siteRepository = siteRepository;
        this.checkpointRepository = checkpointRepository;
        this.baselineRepository = baselineRepository;
        this.segmentRepository = segmentRepository;
        this.sweepWorker = sweepWorker;
    }

    /**
     * Freeze the current checkpoint seqs of every V2 site of the account as SQL baselines
     * (plugin activation).
     */
    @Transactional
    public void captureBaselines(AccountPlugin activation) {
        deltaV2Sites(activation).forEach(site -> capture(activation, site));
    }

    /**
     * Reinit: recapture baselines, re-enqueue the sites' segments for SQL generation, and wake
     * the worker once the surrounding transaction commits.
     */
    @Transactional
    public void recaptureForReinit(AccountPlugin activation) {
        List<Site> sites = deltaV2Sites(activation).toList();
        for (Site site : sites) {
            capture(activation, site);
            int requeued = segmentRepository.clearPluginSqlBySiteId(site.getId());
            log.info("Reinit re-enqueued {} segments for delta SQL: siteId={}", requeued, site.getId());
        }
        if (!sites.isEmpty()) {
            wakeAfterCommit();
        }
    }

    private java.util.stream.Stream<Site> deltaV2Sites(AccountPlugin activation) {
        return siteRepository.findByAccountId(activation.getAccountId()).stream()
                .filter(Site::isDeltaV2);
    }

    private void capture(AccountPlugin activation, Site site) {
        baselineRepository.deleteByAccountPluginIdAndSiteId(activation.getId(), site.getId());
        List<Checkpoint> checkpoints = checkpointRepository.findBySiteId(site.getId());
        for (Checkpoint checkpoint : checkpoints) {
            baselineRepository.save(PluginDeltaBaseline.create(
                    activation.getId(), site.getId(), checkpoint.getTableName(), checkpoint.getSeq()));
        }
        log.info("Captured {} delta SQL baselines: siteId={}, accountPluginId={}",
                checkpoints.size(), site.getId(), activation.getId());
    }

    /**
     * The worker's claim query runs in its own transaction and must see the re-enqueued rows;
     * outside a transaction (unit tests) the wake is immediate.
     */
    private void wakeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sweepWorker.wake();
                }
            });
        } else {
            sweepWorker.wake();
        }
    }
}
