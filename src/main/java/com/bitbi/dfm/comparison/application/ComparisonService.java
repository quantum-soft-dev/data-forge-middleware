package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.domain.FileComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for file comparison operations.
 *
 * <p>This service orchestrates the comparison workflow, coordinating between
 * the domain model, infrastructure services, and external dependencies.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Creating and managing file comparisons</li>
 *   <li>Orchestrating diff generation workflow</li>
 *   <li>Validating batch ownership and authorization</li>
 *   <li>Managing comparison lifecycle (PENDING → IN_PROGRESS → COMPLETED/FAILED)</li>
 * </ul>
 *
 * <p>Note: Full implementation will be completed in Phase 3 (User Story 1) and Phase 4 (User Story 2).
 * This is a skeleton for Phase 2 (Foundational).
 */
@Service
@Transactional
public class ComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);

    private final ComparisonRepository comparisonRepository;

    public ComparisonService(ComparisonRepository comparisonRepository) {
        this.comparisonRepository = comparisonRepository;
    }

    /**
     * Creates a new file comparison between two batches.
     *
     * <p>This is a skeleton method for Phase 2. Full implementation will include:
     * <ul>
     *   <li>Validate batches exist and belong to accountId (US1 - T038)</li>
     *   <li>Verify user ownership via JWT (US1 - T038)</li>
     *   <li>Validate file selection (US1 - T038)</li>
     *   <li>Create FileComparison aggregate with status=PENDING (US1 - T038)</li>
     *   <li>Transition to IN_PROGRESS (US2 - T056)</li>
     *   <li>Fetch file contents from S3 (US2 - T056)</li>
     *   <li>Generate diffs via DiffService (US2 - T056)</li>
     *   <li>Create ComparisonResult entities (US2 - T056)</li>
     *   <li>Update statistics and transition to COMPLETED/FAILED (US2 - T056)</li>
     * </ul>
     *
     * @param currentBatchId the current batch ID (source)
     * @param targetBatchId the target batch ID (comparison baseline)
     * @param accountId the account owner ID (from JWT)
     * @param selectedFileIds list of file IDs to compare (null = all files)
     * @return the created comparison with ID
     * @throws IllegalArgumentException if validation fails
     * @throws BatchNotFoundException if either batch does not exist
     * @throws UnauthorizedAccessException if accountId does not own the batches
     */
    public FileComparison createComparison(
        Long currentBatchId,
        Long targetBatchId,
        Long accountId,
        java.util.List<Long> selectedFileIds
    ) {
        log.info("Creating comparison: currentBatch={}, targetBatch={}, account={}",
            currentBatchId, targetBatchId, accountId);

        try {
            // Phase 2: Basic creation only (validation and diff generation in Phase 3-4)
            FileComparison comparison = new FileComparison(currentBatchId, targetBatchId, accountId);
            FileComparison savedComparison = comparisonRepository.save(comparison);

            // Add MDC context for logging
            MDC.put("comparisonId", savedComparison.getId().toString());
            MDC.put("currentBatchId", currentBatchId.toString());
            MDC.put("targetBatchId", targetBatchId.toString());

            log.info("Comparison created successfully: id={}", savedComparison.getId());
            return savedComparison;

        } finally {
            // Clean up MDC
            MDC.remove("comparisonId");
            MDC.remove("currentBatchId");
            MDC.remove("targetBatchId");
        }
    }

    /**
     * Deletes a comparison.
     *
     * <p>This method will be fully implemented in Phase 9 (User Story 7).
     *
     * @param comparisonId the comparison ID
     * @param accountId the account owner ID (for authorization check)
     * @throws ComparisonNotFoundException if comparison does not exist
     * @throws UnauthorizedAccessException if accountId does not own the comparison
     * @throws IllegalStateException if comparison is IN_PROGRESS
     */
    public void deleteComparison(Long comparisonId, Long accountId) {
        log.info("Deleting comparison: id={}, account={}", comparisonId, accountId);

        FileComparison comparison = comparisonRepository.findById(comparisonId)
            .orElseThrow(() -> new ComparisonNotFoundException("Comparison not found: " + comparisonId));

        if (!comparison.getAccountId().equals(accountId)) {
            throw new UnauthorizedAccessException("User does not own this comparison");
        }

        if (!comparison.canDelete()) {
            throw new IllegalStateException(
                "Cannot delete comparison with status " + comparison.getStatus()
            );
        }

        comparisonRepository.delete(comparison);
        log.info("Comparison deleted successfully: id={}", comparisonId);
    }

    // Custom exceptions
    public static class BatchNotFoundException extends RuntimeException {
        public BatchNotFoundException(String message) {
            super(message);
        }
    }

    public static class UnauthorizedAccessException extends RuntimeException {
        public UnauthorizedAccessException(String message) {
            super(message);
        }
    }

    public static class ComparisonNotFoundException extends RuntimeException {
        public ComparisonNotFoundException(String message) {
            super(message);
        }
    }
}
