package com.bitbi.dfm.comparison.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for {@link FileComparison} aggregate operations.
 *
 * <p>This interface defines the contract for persistence operations on FileComparison aggregates.
 * The implementation resides in the infrastructure layer, following DDD principles.
 *
 * <p>Query methods follow Spring Data naming conventions for automatic implementation.
 */
public interface ComparisonRepository {

    /**
     * Saves a FileComparison aggregate (create or update).
     *
     * @param comparison the comparison to save
     * @return the saved comparison with generated ID
     * @throws IllegalArgumentException if comparison is null
     */
    FileComparison save(FileComparison comparison);

    /**
     * Finds a comparison by its ID.
     *
     * @param id the comparison ID
     * @return Optional containing the comparison if found, empty otherwise
     */
    Optional<FileComparison> findById(Long id);

    /**
     * Finds a comparison by ID with results eagerly loaded.
     * Use this method to prevent N+1 query issues when accessing results.
     *
     * @param id the comparison ID
     * @return Optional containing the comparison with results if found, empty otherwise
     */
    Optional<FileComparison> findByIdWithResults(Long id);

    /**
     * Finds all comparisons for a specific account.
     *
     * @param accountId the account ID
     * @return list of comparisons belonging to the account
     */
    List<FileComparison> findByAccountId(UUID accountId);

    /**
     * Finds all comparisons for a specific account with pagination, ordered by created date descending.
     *
     * @param accountId the account ID
     * @param pageable pagination information
     * @return page of comparisons
     */
    Page<FileComparison> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /**
     * T124: Finds comparisons for a specific account filtered by status with pagination.
     * Results ordered by created date descending (most recent first).
     *
     * Phase 10: List Comparisons (Supporting Feature)
     *
     * @param accountId the account ID
     * @param status the status filter
     * @param pageable pagination information
     * @return page of comparisons matching the status
     */
    Page<FileComparison> findByAccountIdAndStatusOrderByCreatedAtDesc(
        UUID accountId,
        ComparisonStatus status,
        Pageable pageable
    );

    /**
     * Finds all comparisons that reference a specific batch as the current batch.
     *
     * @param batchId the batch ID
     * @return list of comparisons
     */
    List<FileComparison> findByCurrentBatchId(UUID batchId);

    /**
     * Finds all comparisons that reference a specific batch as the target batch.
     *
     * @param batchId the batch ID
     * @return list of comparisons
     */
    List<FileComparison> findByTargetBatchId(UUID batchId);

    /**
     * Checks if a comparison already exists between two batches with a specific status.
     * Useful for preventing duplicate comparisons.
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
     * Useful for monitoring and cleanup tasks (e.g., finding stuck IN_PROGRESS comparisons).
     *
     * @param status the comparison status
     * @return list of comparisons with the given status
     */
    List<FileComparison> findByStatus(ComparisonStatus status);

    /**
     * Finds all IN_PROGRESS comparisons.
     * Useful for monitoring and timeout handling.
     *
     * @return list of comparisons currently in progress
     */
    default List<FileComparison> findInProgressComparisons() {
        return findByStatus(ComparisonStatus.IN_PROGRESS);
    }

    /**
     * Counts the total number of comparisons for an account.
     *
     * @param accountId the account ID
     * @return count of comparisons
     */
    long countByAccountId(UUID accountId);

    /**
     * Deletes a FileComparison aggregate.
     * This will cascade delete all associated ComparisonResult entities.
     *
     * @param comparison the comparison to delete
     * @throws IllegalArgumentException if comparison is null
     * @throws IllegalStateException if comparison.canDelete() returns false
     */
    void delete(FileComparison comparison);

    /**
     * Deletes a comparison by ID.
     * This will cascade delete all associated ComparisonResult entities.
     *
     * @param id the comparison ID
     */
    void deleteById(Long id);

    /**
     * Deletes all comparisons for a specific account.
     * Use with caution - this is a bulk operation.
     *
     * @param accountId the account ID
     * @return number of comparisons deleted
     */
    long deleteByAccountId(UUID accountId);
}
