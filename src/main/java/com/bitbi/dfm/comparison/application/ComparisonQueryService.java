package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import com.bitbi.dfm.comparison.domain.FileComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Query service for read-side comparison operations.
 *
 * <p>This service handles all read operations for comparisons, following CQRS principles
 * by separating queries from commands.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Retrieving individual comparisons</li>
 *   <li>Listing comparisons with filtering and pagination</li>
 *   <li>Retrieving comparison results with filtering</li>
 *   <li>Authorization checks for read operations</li>
 * </ul>
 *
 * <p>Note: Full implementation will be completed in Phase 10-11 (List and Get operations).
 * This is a skeleton for Phase 2 (Foundational).
 */
@Service
@Transactional(readOnly = true)
public class ComparisonQueryService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonQueryService.class);

    private final ComparisonRepository comparisonRepository;

    public ComparisonQueryService(ComparisonRepository comparisonRepository) {
        this.comparisonRepository = comparisonRepository;
    }

    /**
     * Finds a comparison by ID with authorization check.
     *
     * <p>This method will be fully implemented in Phase 11 (User Story: Get Comparison).
     *
     * @param comparisonId the comparison ID
     * @param accountId the account owner ID (for authorization check)
     * @return the comparison
     * @throws ComparisonService.ComparisonNotFoundException if comparison does not exist
     * @throws ComparisonService.UnauthorizedAccessException if accountId does not own the comparison
     */
    public FileComparison findById(Long comparisonId, Long accountId) {
        log.debug("Finding comparison: id={}, account={}", comparisonId, accountId);

        FileComparison comparison = comparisonRepository.findById(comparisonId)
            .orElseThrow(() -> new ComparisonService.ComparisonNotFoundException(
                "Comparison not found: " + comparisonId
            ));

        if (!comparison.getAccountId().equals(accountId)) {
            throw new ComparisonService.UnauthorizedAccessException(
                "User does not own this comparison"
            );
        }

        return comparison;
    }

    /**
     * Finds a comparison by ID with results eagerly loaded.
     *
     * <p>Use this method when you need to access comparison results to prevent N+1 queries.
     *
     * @param comparisonId the comparison ID
     * @param accountId the account owner ID (for authorization check)
     * @return the comparison with results loaded
     * @throws ComparisonService.ComparisonNotFoundException if comparison does not exist
     * @throws ComparisonService.UnauthorizedAccessException if accountId does not own the comparison
     */
    public FileComparison findByIdWithResults(Long comparisonId, Long accountId) {
        log.debug("Finding comparison with results: id={}, account={}", comparisonId, accountId);

        FileComparison comparison = comparisonRepository.findByIdWithResults(comparisonId)
            .orElseThrow(() -> new ComparisonService.ComparisonNotFoundException(
                "Comparison not found: " + comparisonId
            ));

        if (!comparison.getAccountId().equals(accountId)) {
            throw new ComparisonService.UnauthorizedAccessException(
                "User does not own this comparison"
            );
        }

        return comparison;
    }

    /**
     * Lists all comparisons for an account with pagination.
     *
     * <p>This method will be fully implemented in Phase 10 (List Comparisons).
     *
     * @param accountId the account owner ID
     * @param pageable pagination information
     * @return page of comparisons
     */
    public Page<FileComparison> findByAccountId(Long accountId, Pageable pageable) {
        log.debug("Listing comparisons for account: id={}, page={}", accountId, pageable.getPageNumber());
        return comparisonRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
    }

    /**
     * Finds all comparisons for a specific batch.
     *
     * <p>Useful for showing comparison history when viewing a batch.
     *
     * @param batchId the batch ID
     * @param accountId the account owner ID (for authorization filtering)
     * @return list of comparisons
     */
    public List<FileComparison> findByBatchId(Long batchId, Long accountId) {
        log.debug("Finding comparisons for batch: id={}, account={}", batchId, accountId);

        List<FileComparison> comparisons = comparisonRepository.findByCurrentBatchId(batchId);
        // Filter by account (authorization)
        return comparisons.stream()
            .filter(c -> c.getAccountId().equals(accountId))
            .toList();
    }

    /**
     * Finds all IN_PROGRESS comparisons (for monitoring).
     *
     * <p>This method is useful for administrative monitoring and timeout handling.
     *
     * @return list of in-progress comparisons
     */
    public List<FileComparison> findInProgressComparisons() {
        log.debug("Finding all IN_PROGRESS comparisons");
        return comparisonRepository.findInProgressComparisons();
    }

    /**
     * Counts total comparisons for an account.
     *
     * @param accountId the account owner ID
     * @return count of comparisons
     */
    public long countByAccountId(Long accountId) {
        return comparisonRepository.countByAccountId(accountId);
    }
}
