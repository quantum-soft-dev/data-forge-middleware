package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.BatchParquetArtifactStatus;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of {@link ChangelogSegmentRepository}.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaChangelogSegmentRepository
        extends JpaRepository<ChangelogSegment, UUID>, ChangelogSegmentRepository {

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.siteId = :siteId AND s.firstSeq = :firstSeq")
    Optional<ChangelogSegment> findBySiteIdAndFirstSeq(UUID siteId, long firstSeq);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.siteId = :siteId AND s.provisional = false "
            + "ORDER BY s.firstSeq")
    java.util.List<ChangelogSegment> findBySiteIdOrderByFirstSeq(UUID siteId);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.batchId = :batchId AND s.provisional = true "
            + "ORDER BY s.firstSeq")
    java.util.List<ChangelogSegment> findProvisionalByBatchId(UUID batchId);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.siteId = :siteId AND s.provisional = true "
            + "ORDER BY s.firstSeq")
    java.util.List<ChangelogSegment> findProvisionalBySiteId(UUID siteId);

    // flushAutomatically is load-bearing, not decoration. This runs mid-transaction in
    // DeltaSessionCommitTransaction.commit, right after rebaselineService.reset() dirtied SiteSyncState
    // and queued checkpoint deletes. Hibernate auto-flushes before a query only when the query space
    // intersects the pending changes; site_sync_state / checkpoints do not intersect
    // changelog_segments, so without an explicit flush clearAutomatically would detach and silently
    // discard them — leaving rebaseline_requested set after a SUCCESSFUL snapshot and stale
    // checkpoint rows alive.
    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.provisional = false, s.egressAt = NULL, s.pluginSqlAt = NULL "
            + "WHERE s.batchId = :batchId AND s.provisional = true")
    int flipProvisionalByBatchId(UUID batchId);

    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.batchId = :toBatchId "
            + "WHERE s.batchId = :fromBatchId AND s.provisional = true")
    int reassignProvisionalBatch(UUID fromBatchId, UUID toBatchId);

    // Crash/eviction backstop: a provisional segment is only legitimate while the session that wrote
    // it still owns a running batch. A staged session deliberately leaves its batch IN_PROGRESS, so
    // this never touches one that may still resume; everything else is garbage.
    @Override
    @Query(value = """
            SELECT * FROM changelog_segments s
            WHERE s.provisional = TRUE
              AND NOT EXISTS (SELECT 1 FROM batches b
                              WHERE b.id = s.batch_id AND b.status = 'IN_PROGRESS')
            ORDER BY s.created_at
            LIMIT :limit
            """, nativeQuery = true)
    java.util.List<ChangelogSegment> findProvisionalWithoutRunningBatch(int limit);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.batchId = :batchId")
    java.util.List<ChangelogSegment> findByBatchId(UUID batchId);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.batchId = :batchId AND s.provisional = false "
            + "ORDER BY s.firstSeq")
    java.util.List<ChangelogSegment> findByBatchIdOrderByFirstSeq(UUID batchId);

    @Override
    boolean existsByBatchId(UUID batchId);

    @Override
    @Query(value = """
            SELECT s.batch_id AS batchId,
                   SUM(s.record_count) AS totalRecords,
                   (SELECT COUNT(DISTINCT k)
                    FROM changelog_segments s2,
                         jsonb_object_keys(COALESCE(s2.stats, '{}'::jsonb)) AS k
                    WHERE s2.batch_id = s.batch_id) AS tableCount
            FROM changelog_segments s
            WHERE s.batch_id IN (:batchIds)
            GROUP BY s.batch_id
            """, nativeQuery = true)
    java.util.List<com.bitbi.dfm.delta.domain.SegmentBatchAggregate> aggregateByBatchIds(
            java.util.List<UUID> batchIds);

    @Override
    @Query("SELECT DISTINCT s.siteId FROM ChangelogSegment s")
    java.util.List<UUID> findDistinctSiteIds();

    @Override
    @Query(value = """
            SELECT * FROM changelog_segments s
            WHERE s.site_id = :siteId
            ORDER BY s.created_at DESC, s.first_seq DESC
            LIMIT :limit
            """, nativeQuery = true)
    java.util.List<ChangelogSegment> findRecentBySiteId(UUID siteId, int limit);

    // The backoff filter (issue #243) applies to the candidate row only; the head-of-line
    // NOT EXISTS deliberately ignores it, so a deferred segment still blocks its own site — a
    // site's delta files publish in seq order, and skipping past a failing head would break that.
    // Every other site drains meanwhile, which is the whole point of the deferral.
    @Override
    @Query(value = """
            SELECT * FROM changelog_segments s
            WHERE s.egress_at IS NULL
              AND (s.egress_retry_at IS NULL OR s.egress_retry_at <= CAST(:now AS timestamp))
              AND NOT EXISTS (SELECT 1 FROM changelog_segments e
                              WHERE e.site_id = s.site_id
                                AND e.egress_at IS NULL
                                AND e.first_seq < s.first_seq)
            ORDER BY s.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    java.util.List<ChangelogSegment> findNextPendingEgress(int limit, java.time.LocalDateTime now);

    @Override
    @Query("SELECT COUNT(s) FROM ChangelogSegment s WHERE s.egressAt IS NULL")
    long countPendingEgress();

    // Same backoff filter and same head-of-line rule as findNextPendingEgress (issue #243).
    @Override
    @Query(value = """
            SELECT * FROM changelog_segments s
            WHERE s.plugin_sql_at IS NULL
              AND (s.plugin_sql_retry_at IS NULL OR s.plugin_sql_retry_at <= CAST(:now AS timestamp))
              AND NOT EXISTS (SELECT 1 FROM changelog_segments p
                              WHERE p.site_id = s.site_id
                                AND p.plugin_sql_at IS NULL
                                AND p.first_seq < s.first_seq)
            ORDER BY s.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    java.util.List<ChangelogSegment> findNextPendingPluginSql(int limit, java.time.LocalDateTime now);

    // Targeted UPDATEs, not a save of the claimed entity: the claim lock is released before the
    // work (#164), so two replicas can attempt one segment at once and the write has to happen in
    // the database rather than from a snapshot. The statement is **claim-scoped** (review round 3):
    // the marker predicate is the #212 one — a segment whose work has since landed must not be
    // pushed into a cooldown by a straggler — and the attempt count must still be the one this
    // claim saw, so a peer's deferral or a reinit's reset (clearPluginSqlBySiteId zeroes the count)
    // is not undone by a failure that started before it. The residual is stated rather than
    // implied: a reinit of a site whose head was at zero attempts is indistinguishable from no
    // reinit at all, and costs that head one cooldown.
    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.pluginSqlAttempts = s.pluginSqlAttempts + 1, "
            + "s.pluginSqlRetryAt = :retryAt WHERE s.id = :id AND s.pluginSqlAt IS NULL "
            + "AND s.pluginSqlAttempts = :attemptsAtClaim")
    int deferPluginSql(UUID id, java.time.LocalDateTime retryAt, int attemptsAtClaim);

    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.egressAttempts = s.egressAttempts + 1, "
            + "s.egressRetryAt = :retryAt WHERE s.id = :id AND s.egressAt IS NULL "
            + "AND s.egressAttempts = :attemptsAtClaim")
    int deferEgress(UUID id, java.time.LocalDateTime retryAt, int attemptsAtClaim);

    // Targeted UPDATEs of one marker, not a save of the claimed entity (issue #245): since #164
    // the claim lock is released before S3, so a merge of the claim-time snapshot would write the
    // other queue's marker back to NULL. CURRENT_TIMESTAMP matches markFullSnapshotPluginSqlProcessed.
    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.pluginSqlAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int markPluginSqlProcessed(UUID id);

    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.egressAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int markEgressed(UUID id);

    @Override
    @Query("SELECT s.s3Key FROM ChangelogSegment s WHERE s.siteId = :siteId")
    java.util.List<String> findAllS3KeysBySiteId(UUID siteId);

    @Override
    @Query("SELECT s.id AS id, s.s3Key AS s3Key, s.batchId AS batchId, "
            + "s.pluginSqlAt AS pluginSqlAt, s.egressAt AS egressAt "
            + "FROM ChangelogSegment s WHERE s.siteId = :siteId AND s.provisional = false "
            + "AND s.lastSeq <= :checkpointSeq ORDER BY s.firstSeq")
    java.util.List<PrunableSegmentView> findBelowCheckpointBySiteId(UUID siteId, long checkpointSeq);

    @Override
    @Query("SELECT s.firstSeq AS firstSeq, s.lastSeq AS lastSeq FROM ChangelogSegment s "
            + "WHERE s.siteId = :siteId AND s.provisional = false ORDER BY s.firstSeq")
    java.util.List<SegmentSeqRange> findSeqRangesBySiteIdOrderByFirstSeq(UUID siteId);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.siteId = :siteId AND s.provisional = false "
            + "AND s.firstSeq > :afterSeq ORDER BY s.firstSeq")
    java.util.List<ChangelogSegment> findBySiteIdAndFirstSeqGreaterThanOrderByFirstSeq(UUID siteId, long afterSeq);

    // The marker predicate travels with the DELETE (issue #212): a reinit committing between
    // retention's read and this statement re-NULLs plugin_sql_at site-wide, and the freshly
    // re-pended row must survive. flushAutomatically for the same reason the sibling bulk
    // statements carry it; no clearAutomatically — retention loads projections, never entities.
    @Override
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM ChangelogSegment s WHERE s.id = :id "
            + "AND s.pluginSqlAt IS NOT NULL AND s.egressAt IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM BatchParquetArtifact a WHERE a.batchId = s.batchId "
            + "AND a.status IN :unfinishedArtifactStatus)")
    int deleteByIdIfProcessed(UUID id,
                              java.util.Collection<BatchParquetArtifactStatus> unfinishedArtifactStatus);

    @Override
    @Query("SELECT COUNT(s) > 0 FROM ChangelogSegment s WHERE s.siteId = :siteId "
            + "AND s.provisional = false AND s.lastSeq <= :checkpointSeq "
            + "AND (s.pluginSqlAt IS NULL OR s.egressAt IS NULL)")
    boolean existsCommittedPendingBelowCheckpoint(UUID siteId, long checkpointSeq);

    @Override
    @Query("SELECT COALESCE(SUM(CASE WHEN s.pluginSqlAt IS NULL THEN 1 ELSE 0 END), 0) AS pendingPluginSql, "
            + "COALESCE(SUM(CASE WHEN s.egressAt IS NULL THEN 1 ELSE 0 END), 0) AS pendingEgress "
            + "FROM ChangelogSegment s WHERE s.batchId = :batchId AND s.provisional = false")
    PendingQueueWork countPendingQueueWorkByBatchId(UUID batchId);

    @Override
    @Query("SELECT s.id AS id, s.s3Key AS s3Key FROM ChangelogSegment s "
            + "WHERE s.siteId = :siteId AND s.provisional = false")
    java.util.List<CommittedSegmentRef> findCommittedRefsBySiteId(UUID siteId);

    // No clearAutomatically, like deleteBySiteId above: the re-baseline reset keeps its locked
    // SiteSyncState managed across the whole sequence of deletes and mutates it at the end.
    @Override
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM ChangelogSegment s WHERE s.id IN :ids")
    int deleteByIdIn(java.util.List<UUID> ids);

    // No clearAutomatically: the wipe keeps its locked SiteSyncState managed across the whole
    // sequence of bulk deletes and mutates it at the end.
    @Override
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM ChangelogSegment s WHERE s.siteId = :siteId")
    int deleteBySiteId(UUID siteId);

    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.pluginSqlAt = CURRENT_TIMESTAMP "
            + "WHERE s.siteId = :siteId AND s.provisional = false "
            + "AND s.mode = 'FULL_SNAPSHOT' AND s.pluginSqlAt IS NULL")
    int markFullSnapshotPluginSqlProcessed(UUID siteId);

    // flushAutomatically for the same reason as flipProvisionalByBatchId: clearAutomatically alone
    // detaches whatever the caller's transaction has not yet flushed.
    //
    // FULL_SNAPSHOT is excluded on purpose (issue #89): DeltaSqlQueueService routes every snapshot
    // segment it claims to suspendBaselines, so re-enqueueing them suspends the very baselines this
    // requeue exists to apply. Snapshot segments never render as SQL anyway.
    @Override
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ChangelogSegment s SET s.pluginSqlAt = NULL, s.pluginSqlAttempts = 0, "
            + "s.pluginSqlRetryAt = NULL "
            + "WHERE s.siteId = :siteId AND s.provisional = false AND s.mode <> 'FULL_SNAPSHOT'")
    int clearPluginSqlBySiteId(UUID siteId);
}
