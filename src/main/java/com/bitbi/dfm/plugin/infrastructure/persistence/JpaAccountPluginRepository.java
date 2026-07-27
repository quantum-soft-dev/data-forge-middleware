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
     */
    @Override
    @Modifying
    @Query("UPDATE AccountPlugin ap SET ap.lastUsedAt = CURRENT_TIMESTAMP, ap.updatedAt = CURRENT_TIMESTAMP WHERE ap.id = :id")
    void updateLastUsedAtById(@Param("id") Long id);
}
