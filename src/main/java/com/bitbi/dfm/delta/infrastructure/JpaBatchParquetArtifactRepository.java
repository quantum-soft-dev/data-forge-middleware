package com.bitbi.dfm.delta.infrastructure;

import com.bitbi.dfm.delta.domain.BatchParquetArtifact;
import com.bitbi.dfm.delta.domain.BatchParquetArtifactRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

@Repository
public interface JpaBatchParquetArtifactRepository
        extends JpaRepository<BatchParquetArtifact, UUID>, BatchParquetArtifactRepository {

    @Override
    Optional<BatchParquetArtifact> findBySiteIdAndBatchIdAndTableName(
            UUID siteId, UUID batchId, String tableName);

    @Override
    List<BatchParquetArtifact> findByBatchId(UUID batchId);

    /** Caller holds the returned row lock for the whole finalization transaction. */
    @Override
    @Query(value = """
            SELECT * FROM batch_parquet_artifacts
            WHERE status = 'PENDING'
               OR (status = 'FAILED' AND updated_at < :retryBefore)
            ORDER BY updated_at, created_at, table_name
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BatchParquetArtifact> findNextRetryable(LocalDateTime retryBefore, int limit);

    @Override
    @Query("SELECT a.s3Key FROM BatchParquetArtifact a WHERE a.batchId = :batchId AND a.s3Key IS NOT NULL")
    List<String> findS3KeysByBatchId(UUID batchId);

    @Override
    @Query("SELECT a.s3Key FROM BatchParquetArtifact a WHERE a.siteId = :siteId AND a.s3Key IS NOT NULL")
    List<String> findS3KeysBySiteId(UUID siteId);

    @Override
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM BatchParquetArtifact a WHERE a.batchId = :batchId")
    int deleteByBatchId(UUID batchId);

    @Override
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM BatchParquetArtifact a WHERE a.siteId = :siteId")
    int deleteBySiteId(UUID siteId);
}
