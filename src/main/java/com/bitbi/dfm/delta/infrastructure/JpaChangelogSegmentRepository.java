package com.bitbi.dfm.delta.infrastructure;

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

    @Override
    @Query(value = """
            SELECT * FROM changelog_segments s
            WHERE s.egress_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM changelog_segments e
                              WHERE e.site_id = s.site_id
                                AND e.egress_at IS NULL
                                AND e.first_seq < s.first_seq)
            ORDER BY s.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    java.util.List<ChangelogSegment> findNextPendingEgress(int limit);

    @Override
    @Query("SELECT COUNT(s) FROM ChangelogSegment s WHERE s.egressAt IS NULL")
    long countPendingEgress();

    @Override
    @Query(value = """
            SELECT * FROM changelog_segments s
            WHERE s.plugin_sql_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM changelog_segments p
                              WHERE p.site_id = s.site_id
                                AND p.plugin_sql_at IS NULL
                                AND p.first_seq < s.first_seq)
            ORDER BY s.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    java.util.List<ChangelogSegment> findNextPendingPluginSql(int limit);

    @Override
    @Query("SELECT s.s3Key FROM ChangelogSegment s WHERE s.siteId = :siteId")
    java.util.List<String> findAllS3KeysBySiteId(UUID siteId);

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
    @Query("UPDATE ChangelogSegment s SET s.pluginSqlAt = NULL "
            + "WHERE s.siteId = :siteId AND s.provisional = false AND s.mode <> 'FULL_SNAPSHOT'")
    int clearPluginSqlBySiteId(UUID siteId);
}
