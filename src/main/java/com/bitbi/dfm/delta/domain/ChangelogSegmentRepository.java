package com.bitbi.dfm.delta.domain;

import java.time.LocalDateTime;
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

    Optional<ChangelogSegment> findById(UUID id);

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

    /** A completed batch's published raw segments in replay order (036, issue #93). */
    List<ChangelogSegment> findByBatchIdOrderByFirstSeq(UUID batchId);

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
     * Segments still waiting for delta-Parquet egress ({@code egress_at IS NULL}), including any
     * row that is the durable queue entry. Used by {@code delta.egress.pending}.
     */
    long countPendingEgress();

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
     * The S3 keys of a site's <b>committed</b> segments only — the set a re-baseline discards
     * (033: never the provisional snapshot replacing it). The projection twin of
     * {@link #findBySiteIdOrderByFirstSeq}, for a caller that needs keys and nothing else.
     *
     * @param siteId site identifier
     * @return object keys of the site's committed segments
     */
    List<String> findCommittedS3KeysBySiteId(UUID siteId);

    /**
     * Bulk-delete a site's committed segments, sparing provisional ones (issue #212 review: the
     * re-baseline reset used to delete an unbounded backlog row by row inside the
     * {@code SessionEnd} commit, under the {@code site_sync_state} row lock).
     *
     * @param siteId site identifier
     * @return number of segments deleted
     */
    int deleteCommittedBySiteId(UUID siteId);

    /**
     * A light projection of a site's committed below-checkpoint segments, in sequence order —
     * retention's whole input (issue #212). Only the columns the prune decision reads: the row id,
     * the object key and the two queue markers. Deliberately not the entity — the below-checkpoint
     * set is unbounded now that pending segments are held back, and hydrating whole entities
     * (JSONB stats included) for a decision over four columns is what the #212 review removed.
     * Excludes provisional segments, like {@link #findBySiteIdOrderByFirstSeq}.
     *
     * @param siteId        site identifier
     * @param checkpointSeq the site's durable checkpoint pointer
     * @return below-checkpoint segments ({@code last_seq <= checkpointSeq}), oldest first
     */
    List<PrunableSegmentView> findBelowCheckpointBySiteId(UUID siteId, long checkpointSeq);

    /**
     * Delete one segment only if its queue work is done — a single-statement conditional delete
     * (issue #212). The marker predicate travels <em>with</em> the DELETE, so a reinit re-pending
     * the row between retention's read and its delete ({@code clearPluginSqlBySiteId} re-NULLs
     * {@code plugin_sql_at} site-wide) makes the delete a no-op instead of destroying a
     * freshly-pending queue entry; the caller deletes the S3 object only after this reported 1.
     *
     * @param id segment identifier
     * @return 1 if the row was deleted, 0 if it was pending again (or already gone)
     */
    int deleteByIdIfProcessed(UUID id);

    /**
     * How much pending queue work a batch's committed segments still carry (issue #212). Read by
     * the batch deleters — batch retention (the deliberate outer horizon of the queues' retry) and
     * the explicit admin delete — so destroying pending work is counted and logged rather than
     * silent. Provisional segments are excluded: their markers are parked at a sentinel, not owed.
     *
     * @param batchId batch identifier
     * @return pending counts per queue, both zero for a fully processed batch
     */
    PendingQueueWork countPendingQueueWorkByBatchId(UUID batchId);

    /**
     * The queue markers of a site's committed below-checkpoint segments, projected without the
     * entity (issue #212). {@code isPending*} mirror {@link ChangelogSegment#isPendingPluginSql()}
     * / {@link ChangelogSegment#isPendingEgress()}, which own the semantics.
     */
    interface PrunableSegmentView {

        UUID getId();

        String getS3Key();

        LocalDateTime getPluginSqlAt();

        LocalDateTime getEgressAt();

        /** @see ChangelogSegment#isPendingPluginSql() */
        default boolean isPendingPluginSql() {
            return getPluginSqlAt() == null;
        }

        /** @see ChangelogSegment#isPendingEgress() */
        default boolean isPendingEgress() {
            return getEgressAt() == null;
        }
    }

    /** Pending-queue-work counts of one batch's committed segments (issue #212). */
    interface PendingQueueWork {

        long getPendingPluginSql();

        long getPendingEgress();

        /** @return {@code true} when deleting the batch would destroy work a queue still owes */
        default boolean hasAny() {
            return getPendingPluginSql() > 0 || getPendingEgress() > 0;
        }
    }

    /**
     * Mark a site's still-pending {@code FULL_SNAPSHOT} segments as processed for plugin SQL,
     * without generating any (issue #89).
     *
     * <p>Claimed by a baseline recapture as its first act. {@code DeltaSqlQueueService} suspends the
     * site's baselines for every snapshot segment it claims, so a snapshot still sitting in the
     * queue would undo the recapture the moment the next sweep ran. Taking the rows first also takes
     * their locks: a sweep that has not started skips them (SKIP LOCKED), and one already holding
     * them makes the recapture wait until its suspension has committed — either way the recapture
     * has the last word.</p>
     *
     * @param siteId site identifier
     * @return number of snapshot segments taken out of the queue
     */
    int markFullSnapshotPluginSqlProcessed(UUID siteId);

    /**
     * Re-enqueue all of a site's segments for plugin SQL generation (plugin reinit: the
     * checkpoint-lag gap is regenerated under freshly captured baselines).
     *
     * @param siteId site identifier
     * @return number of segments re-enqueued
     */
    int clearPluginSqlBySiteId(UUID siteId);
}
