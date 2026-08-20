package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.QueueRetryBackoff;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * segment pending and the sweep retries. <b>That retry's horizon (issue #212):</b>
 * {@code ChangelogRetentionService} holds a pending segment back from pruning, so the retry is no
 * longer silently bounded by changelog retention. What ends it is the segment being processed, an
 * operator deleting the segment or its batch, a client-initiated re-baseline or history wipe
 * replacing the site's history — or <b>batch retention, the deliberate outer horizon</b>
 * ({@code BatchRetentionService}, per-site {@code retentionDays}, default 45 days), which deletes
 * a retired batch's segments regardless of their markers and counts what it destroys on
 * {@code delta.retention.segments.deleted-pending}. Segments of accounts without an active bit-bi
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

    /** One count per failed attempt on a claimed segment (issue #243). */
    private static final String DEFERRED_METRIC = "sql.generation.delta.segments.deferred";
    /** The subset of those on a segment that has failed at least the configured number of times. */
    private static final String POISONED_METRIC = "sql.generation.delta.segments.poisoned";

    private final ChangelogSegmentRepository segmentRepository;
    private final SiteRepository siteRepository;
    private final AccountPluginRepository accountPluginRepository;
    private final SqlGenerationService sqlGenerationService;
    private final PluginDeltaBaselineRepository baselineRepository;
    private final PluginAuditService pluginAuditService;
    private final MeterRegistry meterRegistry;
    private final QueueRetryBackoff backoff;
    private final ApplicationShutdownSignal shutdownSignal;

    public DeltaSqlQueueService(ChangelogSegmentRepository segmentRepository,
                                SiteRepository siteRepository,
                                AccountPluginRepository accountPluginRepository,
                                SqlGenerationService sqlGenerationService,
                                PluginDeltaBaselineRepository baselineRepository,
                                PluginAuditService pluginAuditService,
                                MeterRegistry meterRegistry,
                                @Value("${plugin.sql-generation.delta-retry-delay-seconds:60}")
                                int retryDelaySeconds,
                                @Value("${plugin.sql-generation.delta-poison-after-attempts:7}")
                                int poisonAfterAttempts,
                                ApplicationShutdownSignal shutdownSignal) {
        this.segmentRepository = segmentRepository;
        this.siteRepository = siteRepository;
        this.accountPluginRepository = accountPluginRepository;
        this.sqlGenerationService = sqlGenerationService;
        this.baselineRepository = baselineRepository;
        this.pluginAuditService = pluginAuditService;
        this.meterRegistry = meterRegistry;
        this.backoff = new QueueRetryBackoff(
                "plugin.sql-generation.delta-retry-delay-seconds", retryDelaySeconds,
                "plugin.sql-generation.delta-poison-after-attempts", poisonAfterAttempts);
        this.shutdownSignal = shutdownSignal;
        // Registered at zero so an alert on either can predate the first failure.
        meterRegistry.counter(DEFERRED_METRIC).increment(0);
        meterRegistry.counter(POISONED_METRIC).increment(0);
    }

    /**
     * Claim and process the next pending segment.
     *
     * @return {@code true} if a segment was processed (keep draining), {@code false} if the
     *         queue is empty
     */
    public boolean processNextPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The #164 guard, checked before anything is claimed (issue #243, review round 1).
            // SqlGenerationService refuses the same way, but from inside the try that turns a
            // failure into this segment's deferral — so a caller that wrapped the drain in a
            // transaction would read as "every segment in the queue is poison data" rather than as
            // the wiring mistake it is.
            throw new IllegalStateException(
                    "Refusing to drain the delta-SQL queue inside an active transaction: the "
                            + "generation waits on the semaphore and then talks to S3, which would "
                            + "hold that transaction's connection throughout (issue #164).");
        }
        List<ChangelogSegment> claimed = segmentRepository.findNextPendingPluginSql(
                1, LocalDateTime.now(ZoneOffset.UTC));
        if (claimed.isEmpty()) {
            return false;
        }
        ChangelogSegment segment = claimed.get(0);

        // Everything after the claim is inside the try (review round 2): the site lookup, the
        // activation lookup and the mark write can fail deterministically too — a segment whose
        // `sites` row is gone threw "Site not found" out of the drain on every wake, spending no
        // attempt and writing no cooldown, which is exactly the permanent global stall this ticket
        // closes, reached through the three statements the deferral did not wrap.
        try {
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
                // throws on failure → mark is skipped → the segment stays the durable queue entry.
                // Since #181 the memory-pressure abort is one such failure (it used to return an
                // empty Optional, indistinguishable from "no changes", and the segment was consumed
                // with its SQL never generated); the condition belongs to the pod rather than the
                // batch, so the next sweep generates normally once the heap recovers.
                sqlGenerationService.generateSqlForBatch(segment.getBatchId(), activation.get().getId());
            }
            segment.markPluginSqlProcessed();
            segmentRepository.save(segment);
            return true;
        } catch (SqlGenerationService.MemoryPressureAbortedException e) {
            // Deliberately not deferred and not counted as this segment's attempt (issue #243).
            // The refusal is a reading of the pod's heap taken before any work, so it is systemic
            // and self-repairing: every segment claimed while it lasts would meet it, and walking
            // this one towards the poisoned report would turn a transient overload into a verdict
            // on the data — the rule #150/#162/#178 already hold elsewhere. Ending the drain is
            // also the right answer here, since the next claim would be refused too.
            throw e;
        } catch (RuntimeException e) {
            deferSegment(segment, e);
            // Stop this drain after one deferral (issue #243, review round 1): continuing would
            // walk the whole backlog during a systemic failure — a bucket outage, sustained
            // semaphore contention — spending an attempt and a cooldown on every pending segment
            // of every site, drowning the poisoned signal and delaying recovery by the accumulated
            // cooldowns. One deferral per wake unblocks the queue, since the deferred segment is
            // now in its cooldown and the next wake claims a different site's head.
            return false;
        }
    }

    /**
     * Hold a segment whose generation failed out of the queue for a while, instead of ending the
     * drain on it (issue #243).
     *
     * <p>{@code findNextPendingPluginSql} orders per-site heads <em>globally</em> and takes one, so
     * before this a batch whose SQL deterministically threw was offered first on every wake and no
     * other site's SQL was ever generated. Deferring costs that site its own order (its later
     * segments still queue behind it, which is the per-site seq contract {@code /sql-changes}
     * depends on) and frees every other site.</p>
     *
     * <p>Nothing is discarded — there is no attempt ceiling that gives up. A segment is the durable
     * queue entry and nothing can re-drive it once its marker is stamped, while the usual causes
     * (an unreadable object, data the declared schema cannot render, a ceiling set too low) are
     * repairable; so the attempt count escalates the reporting instead. The outer horizon stays
     * batch retention (#212, {@code delta.retention.segments.deleted-pending}).</p>
     *
     * <p>The attempt count logged here is this claim's snapshot plus one; the stored count is
     * incremented in the database, so with two replicas attempting one segment the row can be
     * ahead of the line logged here.</p>
     */
    private void deferSegment(ChangelogSegment segment, RuntimeException failure) {
        if (shutdownSignal.isShuttingDown()) {
            // The process ending, not the segment failing — #162's rule: such an ending records no
            // verdict. The segment stays pending and the next process claims it.
            log.info("Delta SQL generation for segment {} ended with the shutdown; it stays pending "
                    + "and spends no attempt", segment.getId());
            return;
        }
        int attempts = segment.getPluginSqlAttempts() + 1;
        LocalDateTime retryAt = backoff.nextRetryAt(LocalDateTime.now(ZoneOffset.UTC), attempts);
        if (segmentRepository.deferPluginSql(segment.getId(), retryAt) == 0) {
            // The marker predicate refused: the work landed on another replica while this attempt
            // was failing, or a reinit/retention moved the row. Reporting it would send an operator
            // after a segment that is already done (review round 1).
            log.debug("Not deferring segment {}: it is no longer pending plugin SQL", segment.getId());
            return;
        }
        meterRegistry.counter(DEFERRED_METRIC).increment();
        if (backoff.isPoisoned(attempts)) {
            meterRegistry.counter(POISONED_METRIC).increment();
            log.error("Bit BI delta SQL failed {} times for segment {} of site {} (batch {}, seq {}..{}) "
                            + "— the segment stays queued and is retried at {}, and its site's later "
                            + "segments wait behind it. Nothing discards it: fix the cause, reinit the "
                            + "plugin, or delete the batch. If this fires for many segments at once "
                            + "the cause is systemic, not the data; "
                            + "plugin.sql-generation.delta-poison-after-attempts sets the threshold",
                    attempts, segment.getId(), segment.getSiteId(), segment.getBatchId(),
                    segment.getFirstSeq(), segment.getLastSeq(), retryAt, failure);
        } else {
            log.warn("Bit BI delta SQL failed for segment {} of site {} (attempt {}, retry at {}): {}",
                    segment.getId(), segment.getSiteId(), attempts, retryAt, failure.toString());
        }
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
