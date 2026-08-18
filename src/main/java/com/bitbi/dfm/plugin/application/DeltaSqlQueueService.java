package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transactional consumer of the delta-SQL work queue (026-bitbi-delta-sql).
 *
 * <p>Claims the per-site head pending segment ({@code FOR UPDATE SKIP LOCKED}), renders it into
 * plugin SQL via {@link SqlGenerationService} (semaphore, idempotency, audit and persistence all
 * reused), and marks it processed. This class opens <em>no</em> transaction of its own
 * (issue #164): the claim query, the skip/snapshot path and the {@code plugin_sql_at} write
 * are short repository transactions, and generation — which first waits on the SQL semaphore
 * and then talks to S3 — runs with nothing open. A failure before the mark leaves the
 * segment pending and the sweep retries. Segments of accounts without an active bit-bi
 * activation are marked processed without generating (they must not accumulate).</p>
 *
 * <p>{@code FULL_SNAPSHOT} segments (source-side rebaseline) emit no SQL: the site's per-table
 * baselines are suspended ({@code Long.MAX_VALUE}) until the user reinitializes the plugin, and
 * an audit warning signals that reinit is required. Tables created after the snapshot have no
 * baseline row (default 0) and keep streaming.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaSqlQueueService {

    private static final Logger log = LoggerFactory.getLogger(DeltaSqlQueueService.class);

    private static final String PLUGIN_ID = "bit-bi";

    private final ChangelogSegmentRepository segmentRepository;
    private final SiteRepository siteRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final SqlGenerationService sqlGenerationService;
    private final PluginDeltaBaselineRepository baselineRepository;
    private final PluginAuditService pluginAuditService;
    private final MeterRegistry meterRegistry;

    public DeltaSqlQueueService(ChangelogSegmentRepository segmentRepository,
                                SiteRepository siteRepository,
                                AccountPluginRepository accountPluginRepository,
                                SqlGenerationService sqlGenerationService,
                                PluginDeltaBaselineRepository baselineRepository,
                                PluginAuditService pluginAuditService,
                                MeterRegistry meterRegistry) {
        this.segmentRepository = segmentRepository;
        this.siteRepository = siteRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.sqlGenerationService = sqlGenerationService;
        this.baselineRepository = baselineRepository;
        this.pluginAuditService = pluginAuditService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Claim and process the next pending segment.
     *
     * @return {@code true} if a segment was processed (keep draining), {@code false} if the
     *         queue is empty
     */
    public boolean processNextPending() {
        List<ChangelogSegment> claimed = segmentRepository.findNextPendingPluginSql(1);
        if (claimed.isEmpty()) {
            return false;
        }
        ChangelogSegment segment = claimed.get(0);

        Site site = siteRepository.findById(segment.getSiteId())
                .orElseThrow(() -> new IllegalStateException("Site not found: " + segment.getSiteId()));

        Optional<AccountPlugin> activation = accountPluginRepository
                .findByAccountIdAndPluginId(site.getAccountId(), PLUGIN_ID)
                .filter(AccountPlugin::isActive);

        if (activation.isEmpty()) {
            log.debug("No active bit-bi activation for account {} — marking segment {} processed",
                    site.getAccountId(), segment.getId());
            meterRegistry.counter("sql.generation.delta.segments.skipped.inactive").increment();
        } else if (DeltaSqlGenerationStrategy.MODE_FULL_SNAPSHOT.equals(segment.getMode())) {
            suspendBaselines(segment, site, activation.get());
        } else {
            // throws on failure → mark is skipped → segment stays pending for the sweep. Since
            // #181 the memory-pressure abort is one such failure (it used to return an empty
            // Optional, indistinguishable from "no changes", and the segment was consumed with
            // its SQL never generated); the condition belongs to the pod rather than the batch,
            // so the next sweep generates normally once the heap recovers.
            sqlGenerationService.generateSqlForBatch(segment.getBatchId(), activation.get().getId());
        }

        segment.markPluginSqlProcessed();
        segmentRepository.save(segment);
        return true;
    }

    /**
     * FULL_SNAPSHOT handling: suspend every known table of the site (existing baseline rows and
     * tables present in the snapshot) until the user reinitializes the plugin.
     *
     * <p>033: a re-baseline too large to buffer arrives as N {@code FULL_SNAPSHOT} segments published
     * together, so this runs once per segment. Suspending is idempotent per table, but the audit
     * entry and the {@code rebaseline.detected} metric are the owner's "reinit required" signal — they
     * fire only when a table actually moved into suspension, so one re-baseline produces one signal
     * however many segments carried it.</p>
     */
    private void suspendBaselines(ChangelogSegment segment, Site site, AccountPlugin activation) {
        Set<String> covered = new HashSet<>();
        Set<String> newlySuspended = new HashSet<>();
        List<PluginDeltaBaseline> existing = baselineRepository
                .findByAccountPluginIdAndSiteId(activation.getId(), site.getId());
        // Whether the site was ALREADY in the suspended state before this segment. A re-baseline now
        // arrives as N segments (033), and tables are spread across them, so "did this segment
        // suspend anything" is not the same question as "is this a new re-baseline": a table first
        // seen in segment 7 would otherwise raise a second reinit signal for the same snapshot.
        boolean alreadySuspended = !existing.isEmpty()
                && existing.stream().allMatch(b -> b.getBaselineSeq() != null
                        && b.getBaselineSeq() == Long.MAX_VALUE);
        for (PluginDeltaBaseline baseline : existing) {
            if (baseline.getBaselineSeq() == null || baseline.getBaselineSeq() != Long.MAX_VALUE) {
                baseline.suspend();
                baselineRepository.save(baseline);
                newlySuspended.add(baseline.getTableName());
            }
            covered.add(baseline.getTableName());
        }

        Map<String, com.bitbi.dfm.delta.domain.TableChangeStats> stats = segment.getStats();
        if (stats != null) {
            for (String tableName : stats.keySet()) {
                if (covered.add(tableName)) {
                    PluginDeltaBaseline suspended =
                            PluginDeltaBaseline.create(activation.getId(), site.getId(), tableName, Long.MAX_VALUE);
                    try {
                        baselineRepository.save(suspended);
                    } catch (DataIntegrityViolationException e) {
                        // Another worker already inserted this table's suspension (#164).
                    }
                    newlySuspended.add(tableName);
                }
            }
        }

        if (alreadySuspended || newlySuspended.isEmpty()) {
            log.debug("Site {} is already suspended for this re-baseline — segment {} raises no "
                    + "second reinit signal", site.getId(), segment.getId());
            return;
        }

        String message = "Site was rebaselined (FULL_SNAPSHOT) — SQL generation is suspended for its tables; "
                + "reinitialize the bit-bi plugin to resume";
        log.warn("{}: siteId={}, batchId={}, tables={}", message, site.getId(), segment.getBatchId(),
                newlySuspended);
        pluginAuditService.logSqlGenerationFailed(
                PLUGIN_ID, site.getAccountId(), segment.getBatchId(), site.getId(), message, 0L);
        meterRegistry.counter("sql.generation.delta.rebaseline.detected").increment();
    }
}
