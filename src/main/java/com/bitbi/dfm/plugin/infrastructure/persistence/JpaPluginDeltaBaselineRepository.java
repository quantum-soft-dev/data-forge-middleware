package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.PluginDeltaBaseline;
import com.bitbi.dfm.plugin.domain.PluginDeltaBaselineRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * JPA implementation of {@link PluginDeltaBaselineRepository}.
 */
@Repository
public interface JpaPluginDeltaBaselineRepository
        extends JpaRepository<PluginDeltaBaseline, Long>, PluginDeltaBaselineRepository {

    @Override
    @Query("SELECT b FROM PluginDeltaBaseline b WHERE b.siteId = :siteId")
    List<PluginDeltaBaseline> findBySiteId(@Param("siteId") UUID siteId);

    @Override
    @Query("SELECT b FROM PluginDeltaBaseline b WHERE b.accountPluginId = :accountPluginId AND b.siteId = :siteId")
    List<PluginDeltaBaseline> findByAccountPluginIdAndSiteId(
            @Param("accountPluginId") Long accountPluginId,
            @Param("siteId") UUID siteId);

    @Override
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM PluginDeltaBaseline b WHERE b.siteId = :siteId")
    int deleteBySiteId(@Param("siteId") UUID siteId);

    /**
     * {@code flushAutomatically} is load-bearing, not decoration (found working #383). This runs
     * mid-transaction in {@code BitBiPlugin.onActivate}, right after the activation row was
     * changed (reactivated, or given its {@code baseline_batch_id}). Hibernate's AUTO flush only
     * flushes entities whose tables this DELETE touches, and {@code account_plugins} is not one of
     * them, so without an explicit flush {@code clearAutomatically} detached the unflushed
     * activation: a reactivated plugin stayed {@code is_active = false} with a key that never
     * authenticated, and every activation of an account with sites lost its baseline batch.
     */
    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM PluginDeltaBaseline b WHERE b.accountPluginId = :accountPluginId AND b.siteId = :siteId")
    int deleteByAccountPluginIdAndSiteId(
            @Param("accountPluginId") Long accountPluginId,
            @Param("siteId") UUID siteId);
}
