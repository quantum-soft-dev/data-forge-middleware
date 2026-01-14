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

    // ==================== Plugin History Methods (Feature 014) ====================

    /**
     * Finds all SQL generations for an account-plugin with pagination.
     * Used for listing generation history in admin UI.
     *
     * @param accountPluginId the account plugin ID
     * @param includeSuperseded whether to include superseded generations
     * @param pageable pagination parameters
     * @return page of SQL generations
     */
    org.springframework.data.domain.Page<PluginSqlGeneration> findByAccountPluginId(
            Long accountPluginId,
            boolean includeSuperseded,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Counts generations and sums file sizes for an account-plugin.
     * Used for clear history summary.
     *
     * @param accountPluginId the account plugin ID
     * @return array containing [count, totalBytes]
     */
    Object[] countAndSumByAccountPluginId(Long accountPluginId);

    /**
     * Gets all S3 keys for an account-plugin.
     * Used for bulk S3 deletion when clearing history.
     *
     * @param accountPluginId the account plugin ID
     * @return list of S3 keys
     */
    List<String> findS3KeysByAccountPluginId(Long accountPluginId);

    /**
     * Deletes all generations for an account-plugin.
     * Used when clearing history.
     *
     * @param accountPluginId the account plugin ID
     */
    void deleteByAccountPluginId(Long accountPluginId);

    /**
     * Finds the active (non-superseded) generation for a source batch.
     * Used for regeneration to find the generation to supersede.
     *
     * @param sourceBatchId the source batch ID
     * @return Optional containing the active generation if found
     */
    Optional<PluginSqlGeneration> findActiveBySourceBatchId(UUID sourceBatchId);

    /**
     * Counts generations for an account-plugin.
     * Used for admin listing to show generation counts.
     *
     * @param accountPluginId the account plugin ID
     * @return count of generations
     */
    long countByAccountPluginId(Long accountPluginId);

    /**
     * Finds batch IDs for an account that have SQL generated.
     * Used to identify batches without SQL generation.
     *
     * @param accountPluginId the account plugin ID
     * @return set of batch IDs that have SQL generations
     */
    java.util.Set<UUID> findGeneratedBatchIdsByAccountPluginId(Long accountPluginId);

    /**
     * Deletes a SQL generation record.
     *
     * @param generation the generation to delete
     */
    void delete(PluginSqlGeneration generation);
}
