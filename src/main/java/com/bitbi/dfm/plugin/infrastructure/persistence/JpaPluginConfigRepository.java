package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA implementation of PluginConfigRepository.
 */
@Repository
public interface JpaPluginConfigRepository extends JpaRepository<PluginConfig, Long>, PluginConfigRepository {

    @Override
    Optional<PluginConfig> findByPluginId(String pluginId);

    @Override
    Optional<PluginConfig> findByClientId(String clientId);

    @Override
    @Query("SELECT pc FROM PluginConfig pc WHERE pc.enabled = true")
    List<PluginConfig> findAllEnabled();

    @Override
    @Query("SELECT CASE WHEN COUNT(pc) > 0 THEN true ELSE false END FROM PluginConfig pc WHERE pc.pluginId = :pluginId AND pc.enabled = true")
    boolean existsByPluginIdAndEnabledTrue(@Param("pluginId") String pluginId);
}
