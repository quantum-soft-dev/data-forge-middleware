package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
