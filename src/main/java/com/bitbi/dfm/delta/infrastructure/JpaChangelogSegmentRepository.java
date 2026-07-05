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
    @Query("SELECT s FROM ChangelogSegment s WHERE s.siteId = :siteId ORDER BY s.firstSeq")
    java.util.List<ChangelogSegment> findBySiteIdOrderByFirstSeq(UUID siteId);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.batchId = :batchId")
    java.util.List<ChangelogSegment> findByBatchId(UUID batchId);

    @Override
    @Query("SELECT s FROM ChangelogSegment s WHERE s.batchId IN :batchIds")
    java.util.List<ChangelogSegment> findByBatchIdIn(java.util.List<UUID> batchIds);

    @Override
    @Query("SELECT DISTINCT s.siteId FROM ChangelogSegment s")
    java.util.List<UUID> findDistinctSiteIds();

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
}
