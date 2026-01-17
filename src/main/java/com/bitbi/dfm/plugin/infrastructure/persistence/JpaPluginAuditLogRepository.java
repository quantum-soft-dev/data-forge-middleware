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
    @Query(value = """
        SELECT * FROM plugin_audit_logs pal
        WHERE (CAST(:pluginId AS VARCHAR) IS NULL OR pal.plugin_id = :pluginId)
        AND (CAST(:accountId AS UUID) IS NULL OR pal.account_id = :accountId)
        AND (CAST(:actionType AS VARCHAR) IS NULL OR pal.action_type = :actionType)
        AND (CAST(:success AS BOOLEAN) IS NULL OR pal.success = :success)
        AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR pal.occurred_at >= :from)
        AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR pal.occurred_at < :to)
        ORDER BY pal.occurred_at DESC
        """, nativeQuery = true)
    Page<PluginAuditLog> findByFilters(
            @Param("pluginId") String pluginId,
            @Param("accountId") UUID accountId,
            @Param("actionType") String actionType,
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

    @Override
    @Query("""
        SELECT pal FROM PluginAuditLog pal
        WHERE pal.pluginId = :pluginId
        AND pal.accountId = :accountId
        ORDER BY pal.occurredAt DESC
        """)
    Page<PluginAuditLog> findByPluginIdAndAccountId(
            @Param("pluginId") String pluginId,
            @Param("accountId") UUID accountId,
            Pageable pageable);

    @Override
    @Query(value = """
        SELECT * FROM plugin_audit_logs pal
        WHERE pal.plugin_id = :pluginId
        AND pal.account_id = :accountId
        AND (CAST(:siteId AS VARCHAR) IS NULL OR pal.metadata->>'siteId' = CAST(:siteId AS VARCHAR))
        AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR pal.occurred_at >= :from)
        AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR pal.occurred_at < :to)
        ORDER BY pal.occurred_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM plugin_audit_logs pal
        WHERE pal.plugin_id = :pluginId
        AND pal.account_id = :accountId
        AND (CAST(:siteId AS VARCHAR) IS NULL OR pal.metadata->>'siteId' = CAST(:siteId AS VARCHAR))
        AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR pal.occurred_at >= :from)
        AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR pal.occurred_at < :to)
        """,
        nativeQuery = true)
    Page<PluginAuditLog> findByPluginIdAndAccountIdWithFilters(
            @Param("pluginId") String pluginId,
            @Param("accountId") UUID accountId,
            @Param("siteId") UUID siteId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
