package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of PluginSqlGenerationRepository.
 */
@Repository
public interface JpaPluginSqlGenerationRepository extends JpaRepository<PluginSqlGeneration, UUID>, PluginSqlGenerationRepository {

    /**
     * Finds all SQL generations for a site created after a given timestamp.
     * Orders by created_at ascending (oldest first) for chronological concatenation.
     */
    @Query("SELECT g FROM PluginSqlGeneration g WHERE g.siteId = :siteId AND g.createdAt > :since ORDER BY g.createdAt ASC")
    List<PluginSqlGeneration> findBySiteIdAndCreatedAtAfter(
        @Param("siteId") UUID siteId,
        @Param("since") LocalDateTime since
    );

    /**
     * Finds a SQL generation by source batch ID.
     */
    Optional<PluginSqlGeneration> findBySourceBatchId(UUID sourceBatchId);

    /**
     * Checks if a SQL generation exists for a given source batch.
     */
    boolean existsBySourceBatchId(UUID sourceBatchId);

    /**
     * Finds all SQL generations for a site.
     */
    List<PluginSqlGeneration> findBySiteId(UUID siteId);

    // ==================== Plugin History Methods (Feature 014) ====================

    /**
     * Finds all SQL generations for an account-plugin with pagination.
     * When includeSuperseded is false, only returns active generations.
     */
    @Override
    default Page<PluginSqlGeneration> findByAccountPluginId(
            Long accountPluginId,
            boolean includeSuperseded,
            Pageable pageable
    ) {
        if (includeSuperseded) {
            return findAllByAccountPluginId(accountPluginId, pageable);
        } else {
            return findByAccountPluginIdAndSuperseded(accountPluginId, false, pageable);
        }
    }

    /**
     * Finds all generations for an account-plugin (including superseded).
     */
    @Query("SELECT g FROM PluginSqlGeneration g WHERE g.accountPluginId = :accountPluginId ORDER BY g.createdAt DESC")
    Page<PluginSqlGeneration> findAllByAccountPluginId(
            @Param("accountPluginId") Long accountPluginId,
            Pageable pageable
    );

    /**
     * Finds generations by account-plugin and superseded status.
     */
    @Query("SELECT g FROM PluginSqlGeneration g WHERE g.accountPluginId = :accountPluginId AND g.superseded = :superseded ORDER BY g.createdAt DESC")
    Page<PluginSqlGeneration> findByAccountPluginIdAndSuperseded(
            @Param("accountPluginId") Long accountPluginId,
            @Param("superseded") boolean superseded,
            Pageable pageable
    );

    /**
     * Counts generations and sums file sizes for an account-plugin.
     */
    @Query("SELECT COUNT(g), COALESCE(SUM(g.fileSizeBytes), 0) FROM PluginSqlGeneration g WHERE g.accountPluginId = :accountPluginId")
    Object[] countAndSumByAccountPluginId(@Param("accountPluginId") Long accountPluginId);

    /**
     * Gets all S3 keys for an account-plugin.
     */
    @Query("SELECT g.s3Key FROM PluginSqlGeneration g WHERE g.accountPluginId = :accountPluginId")
    List<String> findS3KeysByAccountPluginId(@Param("accountPluginId") Long accountPluginId);

    /**
     * Deletes all generations for an account-plugin.
     */
    @Modifying
    @Query("DELETE FROM PluginSqlGeneration g WHERE g.accountPluginId = :accountPluginId")
    void deleteByAccountPluginId(@Param("accountPluginId") Long accountPluginId);

    /**
     * Finds the active (non-superseded) generation for a source batch.
     */
    @Query("SELECT g FROM PluginSqlGeneration g WHERE g.sourceBatchId = :sourceBatchId AND g.superseded = false")
    Optional<PluginSqlGeneration> findActiveBySourceBatchId(@Param("sourceBatchId") UUID sourceBatchId);
}
