package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of AccountPluginRepository.
 */
@Repository
public interface JpaAccountPluginRepository extends JpaRepository<AccountPlugin, Long>, AccountPluginRepository {

    @Override
    Optional<AccountPlugin> findByAccountIdAndPluginId(UUID accountId, String pluginId);

    @Override
    @Query("SELECT ap FROM AccountPlugin ap WHERE ap.accountId = :accountId AND ap.active = true")
    List<AccountPlugin> findActiveByAccountId(@Param("accountId") UUID accountId);

    @Override
    Page<AccountPlugin> findByAccountId(UUID accountId, Pageable pageable);

    @Override
    @Query("SELECT ap FROM AccountPlugin ap WHERE ap.accountId = :accountId AND (:includeInactive = true OR ap.active = true)")
    Page<AccountPlugin> findByAccountId(
            @Param("accountId") UUID accountId,
            @Param("includeInactive") boolean includeInactive,
            Pageable pageable);

    @Override
    @Query("SELECT ap FROM AccountPlugin ap WHERE ap.pluginId = :pluginId AND ap.active = true")
    List<AccountPlugin> findActiveByPluginId(@Param("pluginId") String pluginId);

    @Override
    @Query(value = """
            SELECT * FROM account_plugins ap
            WHERE ap.plugin_id = :pluginId
              AND ap.is_active = true
              AND ap.plugin_data ->> 'login' = :login
            """, nativeQuery = true)
    Optional<AccountPlugin> findActiveByPluginIdAndLogin(@Param("pluginId") String pluginId,
                                                         @Param("login") String login);

    @Override
    @Query("""
            SELECT ap FROM AccountPlugin ap
            WHERE ap.pluginId = :pluginId
              AND ap.active = true
              AND ap.apiKeyLookup = :apiKeyLookup
            """)
    Optional<AccountPlugin> findActiveByPluginIdAndApiKeyLookup(@Param("pluginId") String pluginId,
                                                                @Param("apiKeyLookup") String apiKeyLookup);

    @Override
    @Query("""
            SELECT ap FROM AccountPlugin ap
            WHERE ap.pluginId = :pluginId
              AND ap.active = true
              AND ap.apiKeyLookup IS NULL
            """)
    List<AccountPlugin> findActiveByPluginIdWithoutApiKeyLookup(@Param("pluginId") String pluginId);

    @Override
    @Query("SELECT CASE WHEN COUNT(ap) > 0 THEN true ELSE false END FROM AccountPlugin ap WHERE ap.accountId = :accountId AND ap.pluginId = :pluginId AND ap.active = true")
    boolean existsActiveByAccountIdAndPluginId(
            @Param("accountId") UUID accountId,
            @Param("pluginId") String pluginId);

    @Override
    Page<AccountPlugin> findByPluginIdAndActiveTrue(String pluginId, Pageable pageable);

    /**
     * Updates only the last_used_at and updated_at timestamps for an account-plugin.
     * Uses atomic UPDATE to avoid overwriting concurrent changes to other fields.
     *
     * <p>Native, and only because JPQL has no {@code AT TIME ZONE} (issue #286): both columns are
     * zone-independent {@code TIMESTAMP}s holding a UTC wall clock, and both are also written by
     * the entity as {@code Instant}, which is always UTC. A bare {@code CURRENT_TIMESTAMP} is
     * resolved in the database session's zone — which pgjdbc takes from the JVM's default — so off
     * UTC one column received two different clocks. The database stays the time source (#245).</p>
     */
    @Override
    @Modifying
    @Query(value = "UPDATE account_plugins SET last_used_at = CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp), "
            + "updated_at = CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp) WHERE id = :id",
            nativeQuery = true)
    void updateLastUsedAtById(@Param("id") Long id);

    /**
     * Detaches plugin baselines that point at a batch of the site (issue #89 — history wipe).
     * {@code updatedAt} is deliberately untouched: the activation itself did not change, the batch
     * it happened to reference simply stopped existing.
     */
    @Override
    @Modifying(flushAutomatically = true)
    @Query("UPDATE AccountPlugin ap SET ap.baselineBatchId = NULL "
            + "WHERE ap.baselineBatchId IN (SELECT b.id FROM Batch b WHERE b.siteId = :siteId)")
    int detachBaselineBatchesOfSite(@Param("siteId") UUID siteId);
}
