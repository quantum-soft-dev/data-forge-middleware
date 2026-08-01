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

    /**
     * The row lock only serializes the claim itself — the claim transaction commits {@code BUILDING}
     * immediately, and that status is what keeps other workers off the row until its lease expires.
     * The failure backoff doubles per attempt (capped) so a transient outage gets a wide window
     * while a deterministic failure still uses its attempts up quickly.
     */
    @Override
    @Query(value = """
            SELECT candidate.* FROM batch_parquet_artifacts candidate
            WHERE NOT EXISTS (
                    SELECT 1 FROM batch_parquet_artifacts active
                    WHERE active.batch_id = candidate.batch_id
                      AND active.status = 'BUILDING'
                      AND active.updated_at >= CAST(:now AS timestamp) - make_interval(secs =>
                          CAST(:leaseSeconds AS double precision)))
              AND (candidate.status = 'PENDING'
               OR (candidate.status = 'FAILED' AND candidate.updated_at
                       < CAST(:now AS timestamp) - make_interval(secs =>
                           CAST(:retryDelaySeconds AS double precision)
                               * power(2, LEAST(GREATEST(candidate.attempt_count - 1, 0), 6))))
               OR (candidate.status = 'BUILDING' AND candidate.attempt_count < :maxAttempts
                       AND candidate.updated_at
                       < CAST(:now AS timestamp) - make_interval(secs =>
                           CAST(:leaseSeconds AS double precision))))
            ORDER BY candidate.updated_at, candidate.created_at, candidate.table_name
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BatchParquetArtifact> findNextRetryable(LocalDateTime now, int retryDelaySeconds,
                                                 int leaseSeconds, int maxAttempts, int limit);

    @Override
    @Query(value = """
            SELECT pg_try_advisory_xact_lock(hashtextextended(CAST(:batchId AS text), 0))
            """, nativeQuery = true)
    boolean tryLockBatch(UUID batchId);

    @Override
    @Query(value = """
            SELECT * FROM batch_parquet_artifacts
            WHERE batch_id = :batchId
              AND (status = 'PENDING'
               OR (status = 'FAILED' AND updated_at
                       < CAST(:now AS timestamp) - make_interval(secs =>
                           CAST(:retryDelaySeconds AS double precision)
                               * power(2, LEAST(GREATEST(attempt_count - 1, 0), 6))))
               OR (status = 'BUILDING' AND attempt_count < :maxAttempts AND updated_at
                       < CAST(:now AS timestamp) - make_interval(secs =>
                           CAST(:leaseSeconds AS double precision))))
            ORDER BY table_name
            FOR UPDATE
            """, nativeQuery = true)
    List<BatchParquetArtifact> findRetryableByBatchId(UUID batchId, LocalDateTime now,
                                                      int retryDelaySeconds, int leaseSeconds,
                                                      int maxAttempts);

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE batch_parquet_artifacts
            SET status = 'ABANDONED', claim_token = NULL, last_error = :error, updated_at = :now,
                version = version + 1
            WHERE status = 'BUILDING' AND attempt_count >= :maxAttempts AND updated_at
                < CAST(:now AS timestamp) - make_interval(secs =>
                    CAST(:leaseSeconds AS double precision))
            """, nativeQuery = true)
    int abandonExpiredClaims(LocalDateTime now, int leaseSeconds, int maxAttempts, String error);

    @Override
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE batch_parquet_artifacts SET updated_at = :now
            WHERE id = :id AND claim_token = :claimToken AND status = 'BUILDING'
            """, nativeQuery = true)
    int touchClaim(UUID id, UUID claimToken, LocalDateTime now);

    /**
     * Insert the work row only if it is not there yet. A guarded read-then-insert would race two
     * concurrent enqueues of the same batch onto uk_batch_parquet_artifact, and the resulting
     * constraint violation would surface as a 500 on the download path — or, worse, roll back the
     * batch completion that the BEFORE_COMMIT enqueue runs inside.
     *
     * @return 1 when a row was created, 0 when one already existed
     */
    @Override
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = """
            INSERT INTO batch_parquet_artifacts
                (id, batch_id, site_id, table_name, status, attempt_count, created_at, updated_at, version)
            VALUES (:id, :batchId, :siteId, :tableName, 'PENDING', 0, :now, :now, 0)
            ON CONFLICT (batch_id, table_name) DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(UUID id, UUID batchId, UUID siteId, String tableName, LocalDateTime now);

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
