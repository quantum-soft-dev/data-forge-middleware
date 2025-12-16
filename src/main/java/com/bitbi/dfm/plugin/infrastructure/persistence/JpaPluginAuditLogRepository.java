package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA implementation of PluginAuditLogRepository.
 */
@Repository
public interface JpaPluginAuditLogRepository extends JpaRepository<PluginAuditLog, Long>, PluginAuditLogRepository {

    @Override
    @Query("""
        SELECT pal FROM PluginAuditLog pal
        WHERE (:pluginId IS NULL OR pal.pluginId = :pluginId)
        AND (:accountId IS NULL OR pal.accountId = :accountId)
        AND (:actionType IS NULL OR pal.actionType = :actionType)
        AND (:success IS NULL OR pal.success = :success)
        AND (:from IS NULL OR pal.occurredAt >= :from)
        AND (:to IS NULL OR pal.occurredAt < :to)
        ORDER BY pal.occurredAt DESC
        """)
    Page<PluginAuditLog> findByFilters(
            @Param("pluginId") String pluginId,
            @Param("accountId") UUID accountId,
            @Param("actionType") PluginActionType actionType,
            @Param("success") Boolean success,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Override
    @Query("SELECT pal FROM PluginAuditLog pal WHERE pal.pluginId = :pluginId ORDER BY pal.occurredAt DESC")
    Page<PluginAuditLog> findByPluginId(@Param("pluginId") String pluginId, Pageable pageable);

    @Override
    @Query("SELECT pal FROM PluginAuditLog pal WHERE pal.accountId = :accountId ORDER BY pal.occurredAt DESC")
    Page<PluginAuditLog> findByAccountId(@Param("accountId") UUID accountId, Pageable pageable);

    @Override
    @Query("""
        SELECT COUNT(pal) FROM PluginAuditLog pal
        WHERE pal.actionType = :actionType
        AND pal.occurredAt >= :from
        AND pal.occurredAt < :to
        """)
    long countByActionTypeAndDateRange(
            @Param("actionType") PluginActionType actionType,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
