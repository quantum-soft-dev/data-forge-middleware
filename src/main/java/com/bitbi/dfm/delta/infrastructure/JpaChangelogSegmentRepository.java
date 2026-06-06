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
    @Query("SELECT DISTINCT s.siteId FROM ChangelogSegment s")
    java.util.List<UUID> findDistinctSiteIds();
}
