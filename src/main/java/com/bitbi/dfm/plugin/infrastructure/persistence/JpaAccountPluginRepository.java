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
    @Query("SELECT CASE WHEN COUNT(ap) > 0 THEN true ELSE false END FROM AccountPlugin ap WHERE ap.accountId = :accountId AND ap.pluginId = :pluginId AND ap.active = true")
    boolean existsActiveByAccountIdAndPluginId(
            @Param("accountId") UUID accountId,
            @Param("pluginId") String pluginId);

    /**
     * Internal method for JSONB containment query.
     * Uses PostgreSQL JSONB containment operator @> for efficient key lookup.
     */
    @Query(value = """
        SELECT * FROM account_plugins
        WHERE plugin_id = :pluginId
          AND plugin_data @> CAST(:apiKeyJson AS jsonb)
        """, nativeQuery = true)
    Optional<AccountPlugin> findByPluginIdAndApiKeyJson(
            @Param("pluginId") String pluginId,
            @Param("apiKeyJson") String apiKeyJson);

    /**
     * Finds an active account plugin by plugin ID and API key.
     * Formats the API key as JSON and delegates to the native query.
     */
    @Override
    default Optional<AccountPlugin> findByPluginIdAndApiKey(String pluginId, String apiKey) {
        String apiKeyJson = String.format("{\"apiKey\": \"%s\"}", apiKey);
        return findByPluginIdAndApiKeyJson(pluginId, apiKeyJson);
    }

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
