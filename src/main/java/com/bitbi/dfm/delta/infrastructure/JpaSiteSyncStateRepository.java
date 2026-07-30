package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of {@link SiteSyncStateRepository}.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaSiteSyncStateRepository
        extends JpaRepository<SiteSyncState, UUID>, SiteSyncStateRepository {

    @Override
    @Query("SELECT s FROM SiteSyncState s WHERE s.siteId = :siteId")
    Optional<SiteSyncState> findBySiteId(UUID siteId);

    @Override
    @Query("SELECT s FROM SiteSyncState s WHERE s.siteId IN :siteIds")
    List<SiteSyncState> findBySiteIdIn(Collection<UUID> siteIds);

    @Override
    @Query("SELECT s.siteId FROM SiteSyncState s WHERE s.rebuildRequested = true")
    List<UUID> findSiteIdsWithRebuildRequested();

    /**
     * Conditional single-statement stamp (issue #84): no entity load, no lost update against a
     * concurrent cancellation, and a no-op once recorded. Mirrors the lock-free
     * {@code JpaBatchRepository.touchActivity} pattern used on the same gRPC path.
     */
    @Override
    @Modifying
    @Query("UPDATE SiteSyncState s SET s.rebaselineNotifiedAt = :now "
            + "WHERE s.siteId = :siteId AND s.rebaselineRequested = true AND s.rebaselineNotifiedAt IS NULL")
    int markRebaselineNotified(@Param("siteId") UUID siteId, @Param("now") java.time.LocalDateTime now);
}
