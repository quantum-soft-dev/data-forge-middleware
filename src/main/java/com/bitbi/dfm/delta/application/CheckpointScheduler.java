package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final CheckpointRetryProperties retryProperties;
    private final ApplicationShutdownSignal shutdownSignal;
    private final ReentrantLock buildLock = new ReentrantLock();

    public CheckpointScheduler(CheckpointService checkpointService,
                               ChangelogRetentionService retentionService,
                               ChangelogSegmentRepository segmentRepository,
                               CheckpointRepository checkpointRepository,
                               CheckpointRetryProperties retryProperties,
                               ApplicationShutdownSignal shutdownSignal) {
        this.checkpointService = checkpointService;
        this.retentionService = retentionService;
        this.segmentRepository = segmentRepository;
        this.checkpointRepository = checkpointRepository;
        this.retryProperties = retryProperties;
        this.shutdownSignal = shutdownSignal;
    }

    @Scheduled(cron = "${delta.checkpoint.cron:0 0 2 * * *}")
    public void buildCheckpoints() {
        if (!buildLock.tryLock()) {
            log.info("Checkpoint build already in progress; skipping this tick");
            return;
        }
        try {
            List<UUID> deferred = visit(sitesToVisit());
            // One retry pass, and only one (issue #178, review round 2). The first pass pays at most
            // one full wait and then stops waiting, which alone would hand a 40-minute forced
            // rebuild the whole night: every remaining site would miss its non-blocking acquire and
            // go unbuilt. Coming back once, after the rest of the tick has run, gives the sites
            // deferred during a passing collision their build in the same night — and the retry is
            // itself bounded by the same one-wait rule, so a holder that is still there costs one
            // more wait rather than one per site.
            if (!deferred.isEmpty()) {
                log.info("Retrying {} site(s) deferred behind the checkpoint fold budget",
                        deferred.size());
                visit(deferred);
            }
        } finally {
            buildLock.unlock();
        }
    }

    /**
     * Build and prune each site, paying the fold-budget wait <b>once for the whole pass</b>.
     *
     * <p>Paid per site the wait would multiply: a build that never finishes would turn a 200-site
     * tick into {@code 200 x delta.checkpoint.fold-wait-seconds}, over which this scheduler's own
     * lock skips the following nights and retention freezes for every site instead of the contended
     * one. After one spent wait the pass keeps visiting but takes the budget only when it is free —
     * and the latch is dropped again as soon as a site does get it, whether the build then succeeds
     * or fails, so a collision that ends mid-pass restores the ordinary behaviour for everything
     * after it.</p>
     *
     * <p><b>What this rule costs, stated rather than implied</b> (raised in review). It bounds the
     * tick's duration, not the collision's blast radius: a holder that outlasts both this pass's
     * wait and the retry pass's defers every site, so nothing is built or pruned that night. That
     * is the deliberate trade. Waiting per site instead would build those sites — the tick would
     * simply finish later — but only while the holder eventually releases; a genuinely stuck build
     * would park this thread and hold {@code buildLock} for {@code N x} the wait, through the
     * following nights, achieving nothing either way. So the loss is bounded, visible
     * ({@code delta.checkpoint.builds.deferred}, and one WARN naming the count per pass) and gone
     * by the next tick, and the deployment-level answer is the key: a site whose single build takes
     * longer than {@code delta.checkpoint.fold-wait-seconds} needs that key raised, which
     * {@code delta.checkpoint.fold.wait} now measures directly.</p>
     *
     * @param siteIds the sites to visit, in order
     * @return the sites deferred behind the fold budget, in the order they were visited
     */
    private List<UUID> visit(Collection<UUID> siteIds) {
        List<UUID> deferred = new ArrayList<>();
        boolean foldBudgetIsContended = false;
        for (UUID siteId : siteIds) {
            // The sweep is the outer half of the same decision CheckpointService makes between
            // tables (issue #162): once the context is closing, the remaining sites would each
            // open a transaction and an S3 client that are about to be destroyed. Stop here
            // rather than fail site by site to the end of the list.
            if (shutdownSignal.isShuttingDown()) {
                log.info("Ending the checkpoint tick: the application is shutting down");
                return List.of();
            }
            try {
                checkpointService.buildCheckpoint(siteId, !foldBudgetIsContended);
                foldBudgetIsContended = false;
                retentionService.prune(siteId);
            } catch (CheckpointFoldBudget.BuildDeferredException e) {
                // Another build held the process's fold budget (issue #178) — a forced rebuild
                // beside this sweep, in practice. The site was not visited, so retention is
                // skipped with it (the pointer did not move), and the tick carries on without
                // waiting again: the loud, bounded version of a sweep silently stalled behind
                // one build. Only the spent wait is a WARN; the probes that follow it would
                // otherwise be hundreds of lines about one collision.
                foldBudgetIsContended = true;
                deferred.add(siteId);
                if (e.waitWasSpent()) {
                    log.warn("Deferring site {} this tick: {}", siteId, e.getMessage());
                } else {
                    log.debug("Deferring site {} this tick: {}", siteId, e.getMessage());
                }
            } catch (CheckpointService.FramePresenceUnknownException e) {
                // Not a failure of this site: S3 would not say whether its seed frame is there,
                // so the build declined to conclude anything (issue #157). Logged apart from
                // the catch below because during a read outage this fires for every site in the
                // tick, and calling that "build/retention failed" would send an operator
                // looking at the sites rather than at the bucket policy. Retention is skipped
                // with it — the pointer did not move, so there is nothing new to prune.
                //
                // The latch is cleared here and in the catch below for the same reason it is
                // cleared on success (raised in review): both of these are thrown from *inside* the
                // build, so the site did take the budget — the collision is demonstrably over, and
                // leaving the latch set would make the rest of the pass probe without waiting for
                // no reason. During an S3 read outage this branch fires for every site.
                foldBudgetIsContended = false;
                log.warn("Skipping site {} this tick: {}", siteId, e.getMessage());
            } catch (RuntimeException e) {
                foldBudgetIsContended = false;
                log.warn("Checkpoint build/retention failed for site {}: {}", siteId, e.getMessage());
            }
        }
        if (!deferred.isEmpty()) {
            log.warn("{} of {} site(s) were deferred behind the checkpoint fold budget in this pass",
                    deferred.size(), siteIds.size());
        }
        return deferred;
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
     *
     * <p>A row that has spent {@code delta.checkpoint.max-materialize-attempts} attempts without
     * producing a snapshot is not on this list (issue #149) — that is the whole of the bound, since
     * such a site has no other reason to be visited. It has not been forgotten:
     * {@code delta.checkpoint.tables.given-up} gauges the population that dropped out, and a forced
     * rebuild puts a row back into it. The rematerialize-only count is logged because it is the
     * cheapest read on how much of a tick is retry rather than ingestion.</p>
     */
    private Set<UUID> sitesToVisit() {
        Set<UUID> siteIds = new LinkedHashSet<>(segmentRepository.findDistinctSiteIds());
        int withSegments = siteIds.size();
        siteIds.addAll(checkpointRepository.findSiteIdsWithUnmaterializedCheckpoints(
                retryProperties.maxMaterializeAttempts()));
        int rematerializeOnly = siteIds.size() - withSegments;
        if (rematerializeOnly > 0) {
            log.info("Checkpoint tick: {} site(s) with changelog segments, {} more visited only to "
                    + "rematerialize a missing snapshot", withSegments, rematerializeOnly);
        }
        return siteIds;
    }
}
