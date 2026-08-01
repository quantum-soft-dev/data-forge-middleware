package com.bitbi.dfm.delta.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the completed-batch Parquet manifest and work queue. */
public interface BatchParquetArtifactRepository {

    BatchParquetArtifact save(BatchParquetArtifact artifact);

    Optional<BatchParquetArtifact> findBySiteIdAndBatchIdAndTableName(
            UUID siteId, UUID batchId, String tableName);

    List<BatchParquetArtifact> findByBatchId(UUID batchId);

    List<BatchParquetArtifact> findNextRetryable(int limit);

    List<String> findS3KeysByBatchId(UUID batchId);

    List<String> findS3KeysBySiteId(UUID siteId);

    int deleteByBatchId(UUID batchId);

    int deleteBySiteId(UUID siteId);
}
