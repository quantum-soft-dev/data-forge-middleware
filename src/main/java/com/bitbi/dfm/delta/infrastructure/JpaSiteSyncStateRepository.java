package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.SiteSyncState;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SiteSyncState s WHERE s.siteId = :siteId")
    Optional<SiteSyncState> findBySiteIdForUpdate(UUID siteId);

    @Override
    @Query("SELECT s FROM SiteSyncState s WHERE s.siteId IN :siteIds")
    List<SiteSyncState> findBySiteIdIn(Collection<UUID> siteIds);

    @Override
    @Query("SELECT s.siteId FROM SiteSyncState s WHERE s.rebuildRequested = true")
    List<UUID> findSiteIdsWithRebuildRequested();

    /**
     * Conditional single-statement take of the pending-wipe flag (issue #89): exactly one caller
     * gets the 1, so exactly one runs the Bit BI baseline recapture. The epoch predicate (issue
     * #142) additionally rejects a caller whose checkpoint belongs to a baseline that has since been
     * replaced — it would recapture from an emptied {@code checkpoints} table and spend the flag the
     * first genuine post-wipe checkpoint needs.
     */
    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SiteSyncState s SET s.wipePending = false "
            + "WHERE s.siteId = :siteId AND s.wipePending = true "
            + "AND s.generation = :generation AND s.baselineEpoch = :baselineEpoch")
    int clearWipePending(@Param("siteId") UUID siteId,
                         @Param("generation") long generation,
                         @Param("baselineEpoch") long baselineEpoch);

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

    /**
     * An upsert rather than an UPDATE (issue #345) so that a site with no sync-state row yet — which
     * in production cannot be on a work list, since a session's segments and its row commit
     * together — is still claimed rather than built unclaimed. {@code updated_at} is bound
     * explicitly because the column's default is the session-zone clock (#286). The conflicting
     * row is locked for the statement only, and the WHERE clause is what makes a live claim
     * refuse: then nothing is updated and the count is 0.
     */
    @Override
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = "INSERT INTO site_sync_state "
            + "(site_id, updated_at, checkpoint_claim_token, checkpoint_claim_expires_at) "
            + "VALUES (:siteId, CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp), :token, "
            + "CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp) + :leaseSeconds * INTERVAL '1 second') "
            + "ON CONFLICT (site_id) DO UPDATE SET "
            + "checkpoint_claim_token = EXCLUDED.checkpoint_claim_token, "
            + "checkpoint_claim_expires_at = EXCLUDED.checkpoint_claim_expires_at "
            + "WHERE site_sync_state.checkpoint_claim_token IS NULL "
            + "OR site_sync_state.checkpoint_claim_expires_at "
            + "<= CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp)")
    int claimCheckpointSite(@Param("siteId") UUID siteId,
                            @Param("token") UUID token,
                            @Param("leaseSeconds") long leaseSeconds);

    @Override
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE site_sync_state SET checkpoint_claim_expires_at = "
            + "CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp) + :leaseSeconds * INTERVAL '1 second' "
            + "WHERE site_id = :siteId AND checkpoint_claim_token = :token")
    int renewCheckpointSiteClaim(@Param("siteId") UUID siteId,
                                 @Param("token") UUID token,
                                 @Param("leaseSeconds") long leaseSeconds);

    @Override
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE site_sync_state SET checkpoint_claim_token = NULL, "
            + "checkpoint_claim_expires_at = NULL "
            + "WHERE site_id = :siteId AND checkpoint_claim_token = :token")
    int releaseCheckpointSiteClaim(@Param("siteId") UUID siteId, @Param("token") UUID token);
}
