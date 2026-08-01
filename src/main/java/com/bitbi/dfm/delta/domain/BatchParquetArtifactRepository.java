package com.bitbi.dfm.delta.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

/** Persistence contract for the completed-batch Parquet manifest and work queue. */
public interface BatchParquetArtifactRepository {

    BatchParquetArtifact save(BatchParquetArtifact artifact);

    Optional<BatchParquetArtifact> findBySiteIdAndBatchIdAndTableName(
            UUID siteId, UUID batchId, String tableName);

    List<BatchParquetArtifact> findByBatchId(UUID batchId);

    /**
     * Work worth another attempt: never-claimed rows, plus cooled-down failures that still have
     * attempts left. A failure that used up {@code maxAttempts} is terminal — several failure
     * classes are deterministic (a table with no declared schema, data the schema cannot render,
     * a batch whose segments a re-baseline removed) and would otherwise be rebuilt forever.
     *
     * @param retryBefore only retry failures last touched before this instant
     * @param maxAttempts attempt ceiling; rows at or above it are never returned again
     * @param limit       maximum rows to claim
     */
    List<BatchParquetArtifact> findNextRetryable(LocalDateTime retryBefore, int maxAttempts, int limit);

    List<String> findS3KeysByBatchId(UUID batchId);

    List<String> findS3KeysBySiteId(UUID siteId);

    int deleteByBatchId(UUID batchId);

    int deleteBySiteId(UUID siteId);
}
