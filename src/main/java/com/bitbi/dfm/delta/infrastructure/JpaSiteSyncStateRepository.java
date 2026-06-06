package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
