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
     * A site's committed segments in sequence order, as full entities. Excludes provisional
     * segments (033).
     *
     * <p><b>Test support only since issue #212</b> (review round 2): every production reader moved
     * to a bounded projection — the checkpoint build decides from
     * {@link #findSeqRangesBySiteIdOrderByFirstSeq} and folds
     * {@link #findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq}, retention reads
     * {@link #findBelowCheckpointBySiteId}, the re-baseline reset reads
     * {@link #findCommittedRefsBySiteId} — because the committed set is unbounded now that pending
     * segments are held back. Do not re-adopt this whole-site entity hydration on a production
     * path; reach for (or add) a projection instead.</p>
     */
    List<ChangelogSegment> findBySiteIdOrderByFirstSeq(UUID siteId);

    /**
     * Seq coverage of a site's committed segments, oldest first — two longs per row, never the
     * entity (issue #212 review). The checkpoint build decides "is a lossless refold possible"
     * and "is there new work" from coverage alone, and since #212 held-back pending segments make
     * the committed set unbounded, so hydrating every entity (JSONB stats included) for those two
     * questions is the read this projection replaces.
     *
     * @param siteId site identifier
     * @return {@code (first_seq, last_seq)} of every committed segment, ordered by {@code first_seq}
     */
    List<SegmentSeqRange> findSeqRangesBySiteIdOrderByFirstSeq(UUID siteId);

    /**
     * Full entities of a site's committed segments above a sequence — the fold's actual input:
     * everything above the seed frame's pointer, or everything at all when {@code afterSeq} is 0
     * (a frameless full refold). Ordered by {@code first_seq}, provisional excluded.
     *
     * @param siteId   site identifier
     * @param afterSeq only segments with {@code first_seq > afterSeq} are returned
     * @return committed segments above {@code afterSeq}, oldest first
     */
    List<ChangelogSegment> findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq(UUID siteId, long afterSeq);

    /** One committed segment's seq coverage (issue #212 review). */
    interface SegmentSeqRange {

        long getFirstSeq();

        long getLastSeq();
    }

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
     * <p>A segment whose last attempt failed is held out until its {@code egress_retry_at}
     * (issue #243) — its own site still queues behind it, every other site drains.</p>
     *
     * @param limit maximum segments to claim
     * @param now   the claiming instant (UTC); segments in backoff past it are not offered
     * @return per-site head pending segments, oldest first
     */
    List<ChangelogSegment> findNextPendingEgress(int limit, LocalDateTime now);

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
     * <p>Backoff applies exactly as in {@link #findNextPendingEgress} (issue #243).</p>
     *
     * @param limit maximum segments to claim
     * @param now   the claiming instant (UTC); segments in backoff past it are not offered
     * @return per-site head pending segments, oldest first
     */
    List<ChangelogSegment> findNextPendingPluginSql(int limit, LocalDateTime now);

    /**
     * Record a failed delta-SQL attempt and hold the segment out of the queue until {@code retryAt}
     * (issue #243). Increments the attempt count in the database rather than writing the caller's
     * snapshot, and is <b>claim-scoped</b>: it does nothing when the segment is no longer pending,
     * or when its attempt count has moved since the claim — a peer's deferral, or a reinit's reset
     * (review round 3).
     *
     * @param id              segment identifier
     * @param retryAt         when the segment may be claimed again (UTC)
     * @param attemptsAtClaim the attempt count this claim saw
     * @return 1 if the segment was deferred, 0 if its work had landed or its state moved
     */
    int deferPluginSql(UUID id, LocalDateTime retryAt, int attemptsAtClaim);

    /**
     * The egress twin of {@link #deferPluginSql(UUID, LocalDateTime, int)} (issue #243).
     *
     * @param id              segment identifier
     * @param retryAt         when the segment may be claimed again (UTC)
     * @param attemptsAtClaim the attempt count this claim saw
     * @return 1 if the segment was deferred, 0 if it had been egressed or its state moved
     */
    int deferEgress(UUID id, LocalDateTime retryAt, int attemptsAtClaim);

    /**
     * Stamp {@code plugin_sql_at} and no other column (issue #245).
     *
     * <p>The claim lock is released before the work (#164), so a whole-entity save of the snapshot
     * captured at claim would merge {@code egress_at} back to the value held then — un-marking
     * work the egress worker had already finished. This statement is the success twin of
     * {@link #deferPluginSql(UUID, LocalDateTime, int)}.</p>
     *
     * @param id segment identifier
     * @return 1 if a row was updated, 0 if it is gone
     */
    int markPluginSqlProcessed(UUID id);

    /**
     * Stamp {@code egress_at} and no other column (issue #245). The egress twin of
     * {@link #markPluginSqlProcessed(UUID)}.
     *
     * @param id segment identifier
     * @return 1 if a row was updated, 0 if it is gone
     */
    int markEgressed(UUID id);

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
     * Row id and object key of a site's <b>committed</b> segments — the set a re-baseline discards
     * (033: never the provisional snapshot replacing it), read as one light projection so the
     * reset can delete exactly the rows whose keys it collected (issue #212 review round 2: a
     * blanket site-wide DELETE after a separate key read could take a row committed in between,
     * whose key was never collected — an orphan object until the #158 sweep).
     *
     * @param siteId site identifier
     * @return id + object key of the site's committed segments
     */
    List<CommittedSegmentRef> findCommittedRefsBySiteId(UUID siteId);

    /**
     * Bulk-delete segments by id (issue #212 review: the re-baseline reset used to delete an
     * unbounded backlog row by row inside the {@code SessionEnd} commit, under the
     * {@code site_sync_state} row lock). The caller passes the ids whose keys it collected, so
     * rows and objects keep their identity.
     *
     * @param ids segment ids to delete; must not be empty
     * @return number of segments deleted
     */
    int deleteByIdIn(List<UUID> ids);

    /** One committed segment's row id and object key (issue #212 review round 2). */
    interface CommittedSegmentRef {

        UUID getId();

        String getS3Key();
    }

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
     * Whether any committed below-checkpoint segment of a site still owes queue work
     * (issue #212, review round 2). This is what scopes the checkpoint build's bounded
     * {@code lossy_refold} drain to the state #212 actually created: a frame-gone site whose
     * below-pointer segments are all <em>processed</em> is the pre-#212 population and keeps the
     * never-quiets alarm, while one pinned open by a held-back pending segment drains like
     * {@code history_gone}.
     *
     * @param siteId        site identifier
     * @param checkpointSeq the site's durable checkpoint pointer
     * @return {@code true} if a committed segment with {@code last_seq <= checkpointSeq} has a
     *         {@code NULL} queue marker
     */
    boolean existsCommittedPendingBelowCheckpoint(UUID siteId, long checkpointSeq);

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
     * <p>Clears the retry state too (issue #243): a reinit is the operator saying the cause is
     * gone, so a segment that had accumulated attempts starts from a clean count and is claimable
     * at once instead of sitting out the cooldown its old failures earned.</p>
     *
     * @param siteId site identifier
     * @return number of segments re-enqueued
     */
    int clearPluginSqlBySiteId(UUID siteId);
}
