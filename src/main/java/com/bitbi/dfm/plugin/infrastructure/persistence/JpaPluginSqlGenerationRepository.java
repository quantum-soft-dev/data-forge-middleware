package com.bitbi.dfm.plugin.infrastructure.persistence;

import com.bitbi.dfm.plugin.domain.PluginSqlGeneration;
import com.bitbi.dfm.plugin.domain.PluginSqlGenerationRepository;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
