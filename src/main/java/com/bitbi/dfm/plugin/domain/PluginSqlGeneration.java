package com.bitbi.dfm.plugin.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity tracking SQL file generation events for the Bit BI plugin.
 * Each record represents one SQL file generated from comparing two batches.
 */
@Entity
@Table(name = "plugin_sql_generations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginSqlGeneration {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_plugin_id", nullable = false)
    private Long accountPluginId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "source_batch_id", nullable = false)
    private UUID sourceBatchId;

    @Column(name = "comparison_batch_id")
    private UUID comparisonBatchId;  // nullable for first batch

    @Column(name = "s3_key", nullable = false, length = 1000)
    private String s3Key;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "statement_count", nullable = false)
    private Integer statementCount;

    @Column(name = "insert_count", nullable = false)
    private Integer insertCount;

    @Column(name = "update_count", nullable = false)
    private Integer updateCount;

    @Column(name = "delete_count", nullable = false)
    private Integer deleteCount;

    @Column(name = "files_processed", nullable = false)
    private Integer filesProcessed;

    @Column(name = "generation_duration_ms", nullable = false)
    private Long generationDurationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "superseded", nullable = false)
    private Boolean superseded = false;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    /**
     * Factory method for creating a new SQL generation record.
     *
     * @param accountPluginId The ID of the active account plugin
     * @param siteId The ID of the site
     * @param sourceBatchId The ID of the current batch (source of diff)
     * @param comparisonBatchId The ID of the previous batch (null for first batch)
     * @param s3Key The S3 key where the SQL file is stored
     * @param fileSizeBytes The size of the generated SQL file
     * @param stats The generation statistics
     * @param durationMs The time taken to generate the SQL
     * @return A new PluginSqlGeneration entity
     */
    public static PluginSqlGeneration create(
            Long accountPluginId,
            UUID siteId,
            UUID sourceBatchId,
            UUID comparisonBatchId,
            String s3Key,
            long fileSizeBytes,
            SqlGenerationStats stats,
            long durationMs
    ) {
        PluginSqlGeneration entity = new PluginSqlGeneration();
        entity.id = UUID.randomUUID();
        entity.accountPluginId = accountPluginId;
        entity.siteId = siteId;
        entity.sourceBatchId = sourceBatchId;
        entity.comparisonBatchId = comparisonBatchId;
        entity.s3Key = s3Key;
        entity.fileSizeBytes = fileSizeBytes;
        entity.statementCount = stats.total();
        entity.insertCount = stats.inserts();
        entity.updateCount = stats.updates();
        entity.deleteCount = stats.deletes();
        entity.filesProcessed = stats.filesProcessed();
        entity.generationDurationMs = durationMs;
        entity.createdAt = LocalDateTime.now();
        entity.superseded = false;
        entity.supersededBy = null;
        return entity;
    }

    /**
     * Returns true if this is a first-batch generation (no comparison batch).
     * First batch generations contain only INSERT statements.
     */
    public boolean isFirstBatch() {
        return comparisonBatchId == null;
    }

    /**
     * Returns the statistics as a SqlGenerationStats record.
     */
    public SqlGenerationStats toStats() {
        return new SqlGenerationStats(insertCount, updateCount, deleteCount, filesProcessed);
    }

    /**
     * Returns true if this generation has been superseded by a regeneration.
     */
    public boolean isSuperseded() {
        return Boolean.TRUE.equals(superseded);
    }

    /**
     * Marks this generation as superseded by a new generation.
     * Used when regenerating SQL for a batch.
     *
     * @param newGenerationId The ID of the new generation that supersedes this one
     * @throws IllegalStateException if this generation is already superseded
     */
    public void markAsSuperseded(UUID newGenerationId) {
        if (this.superseded) {
            throw new IllegalStateException("Generation is already superseded: " + this.id);
        }
        if (newGenerationId == null) {
            throw new IllegalArgumentException("New generation ID cannot be null");
        }
        this.superseded = true;
        this.supersededBy = newGenerationId;
    }
}
