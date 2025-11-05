package com.bitbi.dfm.comparison.infrastructure.persistence;

import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository interface for FileComparisonEntity.
 *
 * <p>This interface provides automatic implementation of standard CRUD operations
 * and defines custom query methods for file comparison operations.
 */
@Repository
public interface SpringDataComparisonRepository extends JpaRepository<FileComparisonEntity, Long> {

    /**
     * Finds a comparison by ID with results eagerly loaded using JOIN FETCH.
     * This prevents N+1 query problems when accessing results.
     *
     * @param id the comparison ID
     * @return Optional containing the comparison with results if found
     */
    @Query("SELECT c FROM FileComparisonEntity c LEFT JOIN FETCH c.results WHERE c.id = :id")
    Optional<FileComparisonEntity> findByIdWithResults(@Param("id") Long id);

    /**
     * Finds all comparisons for a specific account.
     *
     * @param accountId the account ID
     * @return list of comparisons
     */
    List<FileComparisonEntity> findByAccountId(UUID accountId);

    /**
     * Finds all comparisons for a specific account with pagination, ordered by created date descending.
     *
     * @param accountId the account ID
     * @param pageable pagination information
     * @return page of comparisons
     */
    Page<FileComparisonEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /**
     * T124: Finds comparisons for a specific account filtered by status with pagination.
     * Results ordered by created date descending (most recent first).
     *
     * Phase 10: List Comparisons (Supporting Feature)
     *
     * Spring Data JPA will automatically implement this based on method naming convention.
     *
     * @param accountId the account ID
     * @param status the status filter
     * @param pageable pagination information
     * @return page of comparisons matching the status
     */
    Page<FileComparisonEntity> findByAccountIdAndStatusOrderByCreatedAtDesc(
        UUID accountId,
        ComparisonStatus status,
        Pageable pageable
    );

    /**
     * Finds all comparisons that reference a specific batch as the current batch.
     *
     * @param currentBatchId the current batch ID
     * @return list of comparisons
     */
    List<FileComparisonEntity> findByCurrentBatchId(UUID currentBatchId);

    /**
     * Finds all comparisons that reference a specific batch as the target batch.
     *
     * @param targetBatchId the target batch ID
     * @return list of comparisons
     */
    List<FileComparisonEntity> findByTargetBatchId(UUID targetBatchId);

    /**
     * Checks if a comparison exists between two batches with a specific status.
     *
     * @param currentBatchId the current batch ID
     * @param targetBatchId the target batch ID
     * @param status the comparison status
     * @return true if such a comparison exists
     */
    boolean existsByCurrentBatchIdAndTargetBatchIdAndStatus(
        UUID currentBatchId,
        UUID targetBatchId,
        ComparisonStatus status
    );

    /**
     * Finds all comparisons with a specific status.
     *
     * @param status the comparison status
     * @return list of comparisons
     */
    List<FileComparisonEntity> findByStatus(ComparisonStatus status);

    /**
     * Counts the total number of comparisons for an account.
     *
     * @param accountId the account ID
     * @return count of comparisons
     */
    long countByAccountId(UUID accountId);

    /**
     * Counts the number of comparisons with a specific status.
     * Used for Micrometer metrics and monitoring.
     *
     * @param status the comparison status
     * @return count of comparisons with the given status
     */
    long countByStatus(ComparisonStatus status);

    /**
     * Counts the number of comparisons with a specific status completed after a given timestamp.
     * Used for monitoring recent failure rates.
     *
     * @param status the comparison status
     * @param completedAt the timestamp threshold
     * @return count of comparisons matching criteria
     */
    long countByStatusAndCompletedAtAfter(ComparisonStatus status, java.time.Instant completedAt);

    /**
     * Deletes all comparisons for a specific account.
     *
     * @param accountId the account ID
     * @return number of comparisons deleted
     */
    long deleteByAccountId(UUID accountId);

    /**
     * Finds all comparison results for a specific comparison.
     *
     * This method is used by ComparisonDownloadService to retrieve all
     * comparison results for ZIP archive generation.
     *
     * @param comparisonId the comparison ID
     * @return list of comparison result entities
     */
    @Query("SELECT r FROM ComparisonResultEntity r WHERE r.comparisonId = :comparisonId")
    List<ComparisonResultEntity> findByComparisonIdWithFiles(@Param("comparisonId") Long comparisonId);
}
