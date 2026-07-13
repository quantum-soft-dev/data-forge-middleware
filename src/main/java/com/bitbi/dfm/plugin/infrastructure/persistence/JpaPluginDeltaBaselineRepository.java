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
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM PluginDeltaBaseline b WHERE b.accountPluginId = :accountPluginId AND b.siteId = :siteId")
    int deleteByAccountPluginIdAndSiteId(
            @Param("accountPluginId") Long accountPluginId,
            @Param("siteId") UUID siteId);
}
