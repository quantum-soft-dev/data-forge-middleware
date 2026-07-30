package com.bitbi.dfm.delta.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ChangelogSegment} persistence (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface ChangelogSegmentRepository {

    ChangelogSegment save(ChangelogSegment segment);

    void deleteById(UUID id);

    Optional<ChangelogSegment> findBySiteIdAndFirstSeq(UUID siteId, long firstSeq);

    /**
     * A site's committed segments in sequence order — the checkpoint fold's input and the set a
     * re-baseline discards. Excludes provisional segments (033): a snapshot still streaming must not
     * be folded on top of the baseline it is about to replace.
     */
    List<ChangelogSegment> findBySiteIdOrderByFirstSeq(UUID siteId);

    /**
     * A site's provisional segments — leftovers of a re-baseline that never reached
     * {@code SessionEnd} (033). Garbage-collected before the next snapshot attempt streams, both to
     * reclaim storage and to free the {@code UNIQUE (site_id, first_seq)} slots they hold.
     *
     * @param siteId site identifier
     * @return provisional segments of the site (any batch)
     */
    List<ChangelogSegment> findProvisionalBySiteId(UUID siteId);

    /**
     * Publish a completed re-baseline: clear {@code provisional} for every segment of the batch, so
     * the whole snapshot becomes visible to the fold and both work queues at once (033). Runs in the
     * commit transaction, right after the previous baseline is discarded.
     *
     * @param batchId the snapshot session's batch
     * @return number of segments published
     */
    int flipProvisionalByBatchId(UUID batchId);

    List<ChangelogSegment> findByBatchId(UUID batchId);

    boolean existsByBatchId(UUID batchId);

    /**
     * Per-batch delta totals for a page of batches (batch history list view, 029): sum of record
     * counts and distinct-table count across each batch's segments, aggregated SQL-side. Batches
     * without segments (v1) simply have no row in the result.
     */
    List<SegmentBatchAggregate> aggregateByBatchIds(List<UUID> batchIds);

    List<UUID> findDistinctSiteIds();

    /**
     * The most recent segments of a site, newest first (Delta Sync UI, B6).
     *
     * @param siteId site identifier
     * @param limit  maximum segments to return
     * @return up to {@code limit} segments ordered by createdAt desc (firstSeq desc as tie-breaker)
     */
    List<ChangelogSegment> findRecentBySiteId(UUID siteId, int limit);

    /**
     * Pick pending egress work: for every site with un-egressed segments, the earliest one
     * (lowest {@code first_seq}) — so delta files publish in seq order per site — locked with
     * {@code FOR UPDATE SKIP LOCKED} so concurrent workers (or instances) never double-process.
     * Must run inside a transaction.
     *
     * @param limit maximum segments to claim
     * @return per-site head pending segments, oldest first
     */
    List<ChangelogSegment> findNextPendingEgress(int limit);

    /**
     * Pick pending Bit BI plugin SQL work (026-bitbi-delta-sql): for every site with segments
     * awaiting SQL generation, the earliest one (lowest {@code first_seq}) — so generations are
     * created in seq order per site — locked with {@code FOR UPDATE SKIP LOCKED} so concurrent
     * workers never double-process. Must run inside a transaction.
     *
     * @param limit maximum segments to claim
     * @return per-site head pending segments, oldest first
     */
    List<ChangelogSegment> findNextPendingPluginSql(int limit);

    /**
     * Re-enqueue all of a site's segments for plugin SQL generation (plugin reinit: the
     * checkpoint-lag gap is regenerated under freshly captured baselines).
     *
     * @param siteId site identifier
     * @return number of segments re-enqueued
     */
    int clearPluginSqlBySiteId(UUID siteId);
}
