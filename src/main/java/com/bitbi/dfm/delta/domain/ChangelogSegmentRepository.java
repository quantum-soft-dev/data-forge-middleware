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
     * A batch's provisional segments — the leftovers of one re-baseline session that never reached
     * {@code SessionEnd} (033). Batch-scoped on purpose: a site-wide sweep would let one session
     * delete another's in-flight snapshot.
     *
     * @param batchId the session's batch
     * @return provisional segments written by that batch
     */
    List<ChangelogSegment> findProvisionalByBatchId(UUID batchId);

    /**
     * A site's provisional segments — used by tests and diagnostics to assert invisibility.
     *
     * @param siteId site identifier
     * @return provisional segments of the site (any batch)
     */
    List<ChangelogSegment> findProvisionalBySiteId(UUID siteId);

    /**
     * Publish a completed re-baseline: clear {@code provisional} for every segment of the batch and
     * un-park its queue markers, so the whole snapshot becomes visible to the fold and enters both
     * work queues at once (033). Runs in the commit transaction, right after the previous baseline
     * is discarded.
     *
     * @param batchId the snapshot session's batch
     * @return number of segments published
     */
    int flipProvisionalByBatchId(UUID batchId);

    /**
     * Move a staged session's provisional segments onto the batch it is being resumed under (033).
     *
     * <p>A resume whose original batch was already reaped runs under a replacement batch. Publication
     * is keyed on the batch, so without this the segments sealed before the drop would never be
     * published — the snapshot would commit a baseline missing everything it streamed first, with the
     * watermark advanced and the client told it succeeded.</p>
     *
     * @param fromBatchId the reaped batch the segments were sealed under
     * @param toBatchId   the replacement batch the resumed session runs under
     * @return number of segments moved
     */
    int reassignProvisionalBatch(UUID fromBatchId, UUID toBatchId);

    /**
     * Provisional segments whose owning batch is no longer running — the crash/eviction backstop
     * (033). A staged session deliberately keeps its batch {@code IN_PROGRESS}, so a session that
     * may still resume is never swept; anything else is unreachable garbage.
     *
     * @param limit maximum segments to return
     * @return orphaned provisional segments, oldest first
     */
    List<ChangelogSegment> findProvisionalWithoutRunningBatch(int limit);

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
     * Every S3 object of a site's changelog, published and provisional alike — the collection step
     * of a history wipe (issue #89), which unlike a re-baseline must leave nothing behind.
     *
     * @param siteId site identifier
     * @return S3 keys of all the site's segments
     */
    List<String> findAllS3KeysBySiteId(UUID siteId);

    /**
     * Delete every segment of a site, published and provisional (issue #89). Must run before the
     * site's batches: {@code changelog_segments.batch_id} has no cascade.
     *
     * @param siteId site identifier
     * @return number of segments deleted
     */
    int deleteBySiteId(UUID siteId);

    /**
     * Re-enqueue all of a site's segments for plugin SQL generation (plugin reinit: the
     * checkpoint-lag gap is regenerated under freshly captured baselines).
     *
     * @param siteId site identifier
     * @return number of segments re-enqueued
     */
    int clearPluginSqlBySiteId(UUID siteId);
}
