package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.domain.FileComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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
 * <p>Phase 3 (User Story 1) implementation: Full validation workflow.
 */
@Service
@Transactional
public class ComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);

    private final ComparisonRepository comparisonRepository;
    private final BatchRepository batchRepository;

    public ComparisonService(
        ComparisonRepository comparisonRepository,
        BatchRepository batchRepository
    ) {
        this.comparisonRepository = comparisonRepository;
        this.batchRepository = batchRepository;
    }

    /**
     * Creates a new file comparison between two batches.
     *
     * <p>Phase 3 (User Story 1) - Full validation workflow:
     * <ul>
     *   <li>✅ Validate batches exist and are COMPLETED (T038)</li>
     *   <li>✅ Verify user ownership via JWT accountId (T038)</li>
     *   <li>✅ Validate file selection (T038)</li>
     *   <li>✅ Create FileComparison aggregate with status=PENDING (T038)</li>
     *   <li>⏳ Transition to IN_PROGRESS (US2 - T056)</li>
     *   <li>⏳ Fetch file contents from S3 (US2 - T056)</li>
     *   <li>⏳ Generate diffs via DiffService (US2 - T056)</li>
     *   <li>⏳ Create ComparisonResult entities (US2 - T056)</li>
     *   <li>⏳ Update statistics and transition to COMPLETED/FAILED (US2 - T056)</li>
     * </ul>
     *
     * @param currentBatchId the current batch ID (source)
     * @param targetBatchId the target batch ID (comparison baseline)
     * @param accountId the account owner ID (from JWT)
     * @param selectedFileIds list of file IDs to compare (null = all files)
     * @return the created comparison with ID
     * @throws IllegalArgumentException if validation fails (same batch, empty file list)
     * @throws BatchNotFoundException if either batch does not exist
     * @throws BatchNotCompletedException if either batch is not in COMPLETED status
     * @throws UnauthorizedAccessException if accountId does not own the batches
     */
    public FileComparison createComparison(
        UUID currentBatchId,
        UUID targetBatchId,
        UUID accountId,
        List<UUID> selectedFileIds
    ) {
        log.info("Creating comparison: currentBatch={}, targetBatch={}, account={}",
            currentBatchId, targetBatchId, accountId);

        try {
            // T038: Validate file selection
            validateFileSelection(selectedFileIds);

            // T038: Validate batches exist and are COMPLETED
            Batch currentBatch = validateBatchExistsAndCompleted(currentBatchId, "Current");
            Batch targetBatch = validateBatchExistsAndCompleted(targetBatchId, "Target");

            // T038: Verify user ownership via JWT accountId
            validateBatchOwnership(currentBatch, accountId, "current");
            validateBatchOwnership(targetBatch, accountId, "target");

            // T038: Validate batches belong to the same account (redundant but defensive)
            if (!currentBatch.getAccountId().equals(targetBatch.getAccountId())) {
                throw new IllegalArgumentException(
                    "Batches must belong to the same account"
                );
            }

            // T038: Create FileComparison aggregate with status=PENDING
            FileComparison comparison = new FileComparison(currentBatchId, targetBatchId, accountId);
            FileComparison savedComparison = comparisonRepository.save(comparison);

            // Add MDC context for logging
            MDC.put("comparisonId", savedComparison.getId().toString());
            MDC.put("currentBatchId", currentBatchId.toString());
            MDC.put("targetBatchId", targetBatchId.toString());

            log.info("Comparison created successfully: id={}, status={}",
                savedComparison.getId(), savedComparison.getStatus());
            return savedComparison;

        } finally {
            // Clean up MDC
            MDC.remove("comparisonId");
            MDC.remove("currentBatchId");
            MDC.remove("targetBatchId");
        }
    }

    /**
     * Validates file selection input.
     *
     * @param selectedFileIds list of file IDs (can be null for "all files")
     * @throws IllegalArgumentException if list is empty (not null but empty)
     */
    private void validateFileSelection(List<UUID> selectedFileIds) {
        if (selectedFileIds != null && selectedFileIds.isEmpty()) {
            throw new IllegalArgumentException(
                "At least one file must be selected for comparison. Use null to compare all files."
            );
        }
    }

    /**
     * Validates that a batch exists and is in COMPLETED status.
     *
     * @param batchId the batch ID to validate
     * @param batchLabel label for error messages ("Current" or "Target")
     * @return the validated batch
     * @throws BatchNotFoundException if batch does not exist
     * @throws BatchNotCompletedException if batch is not COMPLETED
     */
    private Batch validateBatchExistsAndCompleted(UUID batchId, String batchLabel) {
        Batch batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new BatchNotFoundException(
                batchLabel + " batch " + batchId + " does not exist or is not in COMPLETED status"
            ));

        if (batch.getStatus() != BatchStatus.COMPLETED) {
            throw new BatchNotCompletedException(
                batchLabel + " batch " + batchId + " is not in COMPLETED status (current status: " + batch.getStatus() + ")"
            );
        }

        return batch;
    }

    /**
     * Validates that the authenticated user owns the batch.
     *
     * @param batch the batch to validate
     * @param accountId the account ID from JWT
     * @param batchType type for error messages ("current" or "target")
     * @throws UnauthorizedAccessException if user does not own the batch
     */
    private void validateBatchOwnership(Batch batch, UUID accountId, String batchType) {
        if (!batch.getAccountId().equals(accountId)) {
            throw new UnauthorizedAccessException(
                "Access denied: You do not have permission to access " + batchType + " batch " + batch.getId()
            );
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
    public void deleteComparison(Long comparisonId, UUID accountId) {
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

    public static class BatchNotCompletedException extends RuntimeException {
        public BatchNotCompletedException(String message) {
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
