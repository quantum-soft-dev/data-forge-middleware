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

    List<ChangelogSegment> findBySiteIdOrderByFirstSeq(UUID siteId);

    List<ChangelogSegment> findByBatchId(UUID batchId);

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
