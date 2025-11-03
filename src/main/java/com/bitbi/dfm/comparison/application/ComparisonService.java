package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.comparison.domain.*;
import com.bitbi.dfm.comparison.infrastructure.S3FileContentService;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

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
    private final UploadedFileRepository uploadedFileRepository;
    private final S3FileContentService s3FileContentService;
    private final DiffService diffService;

    public ComparisonService(
        ComparisonRepository comparisonRepository,
        BatchRepository batchRepository,
        UploadedFileRepository uploadedFileRepository,
        S3FileContentService s3FileContentService,
        DiffService diffService
    ) {
        this.comparisonRepository = comparisonRepository;
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.s3FileContentService = s3FileContentService;
        this.diffService = diffService;
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

            // T056: Execute comparison workflow (Phase 4 - User Story 2)
            executeComparison(savedComparison, currentBatch, targetBatch, selectedFileIds);

            return savedComparison;

        } finally {
            // Clean up MDC
            MDC.remove("comparisonId");
            MDC.remove("currentBatchId");
            MDC.remove("targetBatchId");
        }
    }

    /**
     * T056: Executes the comparison workflow for User Story 2.
     *
     * <p>Workflow steps:
     * <ol>
     *   <li>Transition to IN_PROGRESS</li>
     *   <li>Fetch file contents from S3</li>
     *   <li>Generate diffs via DiffService</li>
     *   <li>Create ComparisonResult entities</li>
     *   <li>Update statistics</li>
     *   <li>Transition to COMPLETED/FAILED</li>
     * </ol>
     *
     * @param comparison the comparison aggregate
     * @param currentBatch the current batch
     * @param targetBatch the target batch
     * @param selectedFileIds list of selected file IDs (null = all files)
     */
    private void executeComparison(
        FileComparison comparison,
        Batch currentBatch,
        Batch targetBatch,
        List<UUID> selectedFileIds
    ) {
        try {
            // Step 1: Transition to IN_PROGRESS
            comparison.startComparison();
            comparisonRepository.save(comparison);
            log.info("Comparison started: id={}, status={}", comparison.getId(), comparison.getStatus());

            // Step 2: Get files to compare
            List<UploadedFile> filesToCompare = getFilesToCompare(currentBatch.getId(), selectedFileIds);
            log.info("Found {} files to compare", filesToCompare.size());

            // Step 3: Build target files lookup map (by filename)
            Map<String, UploadedFile> targetFilesByName = uploadedFileRepository
                .findByBatchId(targetBatch.getId())
                .stream()
                .collect(Collectors.toMap(UploadedFile::getOriginalFileName, f -> f));

            // Step 4: Compare each file
            int processedCount = 0;
            for (UploadedFile currentFile : filesToCompare) {
                try {
                    processFileComparison(comparison, currentFile, targetFilesByName);
                    processedCount++;

                    if (processedCount % 10 == 0) {
                        log.info("Processed {}/{} files", processedCount, filesToCompare.size());
                    }
                } catch (Exception e) {
                    log.error("Error comparing file {}: {}", currentFile.getOriginalFileName(), e.getMessage(), e);
                    // Continue with other files instead of failing entire comparison
                }
            }

            // Step 5: Complete comparison
            comparison.completeComparison();
            comparisonRepository.save(comparison);
            log.info("Comparison completed successfully: id={}, totalFiles={}, changed={}, added={}, unchanged={}",
                comparison.getId(),
                comparison.getTotalFilesCompared(),
                comparison.getFilesChanged(),
                comparison.getFilesAdded(),
                comparison.getFilesUnchanged());

        } catch (Exception e) {
            log.error("Comparison failed: id={}, error={}", comparison.getId(), e.getMessage(), e);
            comparison.failComparison("Comparison failed: " + e.getMessage());
            comparisonRepository.save(comparison);
            throw new ComparisonExecutionException("Comparison execution failed", e);
        }
    }

    /**
     * Gets the list of files to compare based on selection.
     *
     * @param currentBatchId the current batch ID
     * @param selectedFileIds list of selected file IDs (null = all files)
     * @return list of files to compare
     */
    private List<UploadedFile> getFilesToCompare(UUID currentBatchId, List<UUID> selectedFileIds) {
        if (selectedFileIds == null || selectedFileIds.isEmpty()) {
            // Compare all files in current batch
            return uploadedFileRepository.findByBatchId(currentBatchId);
        } else {
            // Compare only selected files
            return uploadedFileRepository.findAllById(selectedFileIds);
        }
    }

    /**
     * Processes comparison for a single file.
     *
     * <p>Steps:
     * <ol>
     *   <li>Check if file exists in target batch (by name)</li>
     *   <li>Fetch file contents from S3</li>
     *   <li>Generate diff via DiffService</li>
     *   <li>Create ComparisonResult entity</li>
     *   <li>Add result to comparison aggregate</li>
     * </ol>
     *
     * @param comparison the comparison aggregate
     * @param currentFile the current file to compare
     * @param targetFilesByName map of target files by filename
     */
    private void processFileComparison(
        FileComparison comparison,
        UploadedFile currentFile,
        Map<String, UploadedFile> targetFilesByName
    ) {
        String fileName = currentFile.getOriginalFileName();
        UploadedFile targetFile = targetFilesByName.get(fileName);

        // Fetch current file content from S3
        String currentContent = s3FileContentService.fetchFileContent(currentFile.getS3Key());

        // Fetch target file content (if exists)
        String targetContent = null;
        String targetFileName = null;
        if (targetFile != null) {
            targetContent = s3FileContentService.fetchFileContent(targetFile.getS3Key());
            targetFileName = targetFile.getOriginalFileName();
        }

        // Generate diff
        DiffService.DiffResult diffResult = diffService.generateDiff(
            currentContent,
            targetContent,
            fileName,
            targetFileName
        );

        // Create ComparisonResult entity
        ComparisonResult result = new ComparisonResult(
            comparison.getId(),  // comparisonId
            currentFile.getId(), // fileId
            targetFile != null ? targetFile.getId() : null, // targetFileId
            diffResult.changeType(),
            diffResult.unifiedDiffJson(),
            diffResult.lineAdditions(),
            diffResult.lineDeletions(),
            diffResult.changeSize()
        );

        // Add result to comparison aggregate (updates statistics)
        comparison.addResult(result);

        log.debug("File comparison completed: file={}, changeType={}, additions={}, deletions={}",
            fileName, diffResult.changeType(), diffResult.lineAdditions(), diffResult.lineDeletions());
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

    public static class ComparisonExecutionException extends RuntimeException {
        public ComparisonExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
