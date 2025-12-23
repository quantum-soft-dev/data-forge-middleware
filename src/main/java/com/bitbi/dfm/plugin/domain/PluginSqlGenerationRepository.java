package com.bitbi.dfm.plugin.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for PluginSqlGeneration entities.
 * Follows DDD pattern with interface in domain layer.
 */
public interface PluginSqlGenerationRepository {

    /**
     * Saves a new SQL generation record.
     */
    PluginSqlGeneration save(PluginSqlGeneration generation);

    /**
     * Finds a SQL generation by its ID.
     */
    Optional<PluginSqlGeneration> findById(UUID id);

    /**
     * Finds all SQL generations for a site created after a given timestamp.
     * Used by the Plugin API to retrieve SQL changes.
     * Results are ordered by creation time ascending (oldest first).
     *
     * @param siteId The site ID to filter by
     * @param since The timestamp to filter by (exclusive)
     * @return List of SQL generations ordered by created_at ASC
     */
    List<PluginSqlGeneration> findBySiteIdAndCreatedAtAfter(UUID siteId, LocalDateTime since);

    /**
     * Finds a SQL generation by source batch ID.
     * Each source batch can have at most one SQL generation (unique constraint).
     *
     * @param sourceBatchId The source batch ID
     * @return Optional containing the generation if found
     */
    Optional<PluginSqlGeneration> findBySourceBatchId(UUID sourceBatchId);

    /**
     * Checks if a SQL generation already exists for a given source batch.
     *
     * @param sourceBatchId The source batch ID to check
     * @return true if a generation exists
     */
    boolean existsBySourceBatchId(UUID sourceBatchId);

    /**
     * Finds all SQL generations for a site.
     * Used for testing and debugging.
     *
     * @param siteId The site ID to filter by
     * @return List of SQL generations for the site
     */
    List<PluginSqlGeneration> findBySiteId(UUID siteId);

    /**
     * Finds all SQL generations.
     * Used for testing.
     *
     * @return List of all SQL generations
     */
    List<PluginSqlGeneration> findAll();
}
