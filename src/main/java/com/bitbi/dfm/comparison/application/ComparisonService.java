package com.bitbi.dfm.comparison.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.domain.BatchStatus;
import com.bitbi.dfm.comparison.domain.*;
import com.bitbi.dfm.comparison.infrastructure.S3FileContentService;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private final MeterRegistry meterRegistry;

    // T062: Micrometer counters for monitoring
    private final Counter comparisonCreatedCounter;
    private final Counter comparisonCompletedCounter;
    private final Counter comparisonFailedCounter;

    public ComparisonService(
        ComparisonRepository comparisonRepository,
        BatchRepository batchRepository,
        UploadedFileRepository uploadedFileRepository,
        S3FileContentService s3FileContentService,
        DiffService diffService,
        MeterRegistry meterRegistry
    ) {
        this.comparisonRepository = comparisonRepository;
        this.batchRepository = batchRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.s3FileContentService = s3FileContentService;
        this.diffService = diffService;
        this.meterRegistry = meterRegistry;

        // T062: Initialize Micrometer counters
        this.comparisonCreatedCounter = Counter.builder("comparison.created")
            .description("Number of comparisons created")
            .register(meterRegistry);

        this.comparisonCompletedCounter = Counter.builder("comparison.completed")
            .description("Number of comparisons completed successfully")
            .register(meterRegistry);

        this.comparisonFailedCounter = Counter.builder("comparison.failed")
            .description("Number of comparisons that failed")
            .register(meterRegistry);
    }

    /**
     * Creates a new file comparison between two batches.
     *
     * <p>Phase 3 (User Story 1) - Full validation workflow:
     * <ul>
     *   <li>✅ Validate batches exist and have files (T038 - Updated)</li>
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
     * <p><strong>Business Rule Change (2025-11-03):</strong> Batches no longer need to be in COMPLETED status.
     * Any batch with files can be compared, regardless of status (IN_PROGRESS, COMPLETED, FAILED).
     * This allows users to compare partial uploads or failed batches if needed.
     *
     * @param currentBatchId the current batch ID (source)
     * @param targetBatchId the target batch ID (comparison baseline)
     * @param accountId the account owner ID (from JWT)
     * @param selectedFileIds list of file IDs to compare (null = all files)
     * @return the created comparison with ID
     * @throws IllegalArgumentException if validation fails (same batch, empty file list, no files in batch)
     * @throws BatchNotFoundException if either batch does not exist
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

            // T038: Validate batches exist and have files (Updated: No longer requires COMPLETED status)
            Batch currentBatch = validateBatchExistsAndHasFiles(currentBatchId, "Current");
            Batch targetBatch = validateBatchExistsAndHasFiles(targetBatchId, "Target");

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

            // T062: Increment created counter
            comparisonCreatedCounter.increment();

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
     * T061: Creates a new file comparison asynchronously for large file sets.
     *
     * <p>This method is designed for large comparisons (>100 files) that may take
     * longer than typical HTTP request timeouts. It uses Spring's @Async annotation
     * to process the comparison in a background thread pool.
     *
     * <p>Workflow:
     * <ol>
     *   <li>Create FileComparison record with status=PENDING</li>
     *   <li>Return immediately with comparison ID</li>
     *   <li>Process comparison asynchronously in background thread</li>
     *   <li>Client polls for completion via GET /api/v1/comparisons/{id}</li>
     * </ol>
     *
     * <p>Benefits:
     * <ul>
     *   <li>Non-blocking: API responds immediately</li>
     *   <li>Scalable: Uses bounded thread pool (max 5 concurrent)</li>
     *   <li>Resilient: Failures logged, comparison marked as FAILED</li>
     *   <li>Observable: Client can poll for status updates</li>
     * </ul>
     *
     * @param currentBatchId the current batch ID (source)
     * @param targetBatchId the target batch ID (comparison baseline)
     * @param accountId the account owner ID (from JWT)
     * @param selectedFileIds list of file IDs to compare (null = all files)
     * @return CompletableFuture containing the comparison result
     */
    @Async("comparisonExecutor")
    @Transactional
    public CompletableFuture<FileComparison> createComparisonAsync(
        UUID currentBatchId,
        UUID targetBatchId,
        UUID accountId,
        List<UUID> selectedFileIds
    ) {
        log.info("[ASYNC] Creating comparison asynchronously: currentBatch={}, targetBatch={}, account={}",
            currentBatchId, targetBatchId, accountId);

        try {
            // Perform all validation and comparison logic synchronously within the async thread
            FileComparison result = createComparison(currentBatchId, targetBatchId, accountId, selectedFileIds);

            log.info("[ASYNC] Comparison completed asynchronously: id={}, status={}",
                result.getId(), result.getStatus());

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("[ASYNC] Async comparison failed: currentBatch={}, targetBatch={}, error={}",
                currentBatchId, targetBatchId, e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * T056: Executes the comparison workflow for User Story 2.
     * T062: Instrumented with Micrometer @Timed annotation for performance monitoring.
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
     * <p>Metrics:
     * <ul>
     *   <li>Timer: comparison.duration (tracks execution time)</li>
     *   <li>Counter: comparison.completed (incremented on success)</li>
     *   <li>Counter: comparison.failed (incremented on failure)</li>
     * </ul>
     *
     * @param comparison the comparison aggregate
     * @param currentBatch the current batch
     * @param targetBatch the target batch
     * @param selectedFileIds list of selected file IDs (null = all files)
     */
    @Timed(value = "comparison.duration", description = "Time taken to execute comparison")
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

            // T062: Increment completed counter
            comparisonCompletedCounter.increment();

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

            // T062: Increment failed counter
            comparisonFailedCounter.increment();

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
     * T063: Enhanced with comprehensive error handling for S3 and encoding failures.
     *
     * <p>Steps:
     * <ol>
     *   <li>Check if file exists in target batch (by name)</li>
     *   <li>Fetch file contents from S3 (with error handling)</li>
     *   <li>Generate diff via DiffService</li>
     *   <li>Create ComparisonResult entity</li>
     *   <li>Add result to comparison aggregate</li>
     * </ol>
     *
     * <p>Error handling:
     * <ul>
     *   <li>FileNotFoundException: Log warning, skip file</li>
     *   <li>FileAccessDeniedException: Log error, rethrow (critical)</li>
     *   <li>BinaryFileException: Log info, skip file with appropriate message</li>
     *   <li>FileTooLargeException: Log warning, skip file</li>
     *   <li>FileContentRetrievalException: Log error, skip file</li>
     * </ul>
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

        try {
            // T063: Fetch current file content from S3 with comprehensive error handling
            String currentContent;
            try {
                currentContent = s3FileContentService.fetchFileContent(currentFile.getS3Key());
            } catch (S3FileContentService.FileNotFoundException e) {
                log.warn("Current file not found in S3, skipping: file={}, key={}", fileName, currentFile.getS3Key());
                return; // Skip this file
            } catch (S3FileContentService.FileAccessDeniedException e) {
                log.error("Access denied to current file in S3: file={}, key={}", fileName, currentFile.getS3Key());
                throw e; // Rethrow - this is a critical error indicating configuration issue
            } catch (S3FileContentService.BinaryFileException e) {
                log.info("Binary file detected (cannot compare), skipping: file={}, key={}", fileName, currentFile.getS3Key());
                return; // Skip binary files per FR-016
            } catch (S3FileContentService.FileTooLargeException e) {
                log.warn("File too large for comparison, skipping: file={}, key={}, error={}", fileName, currentFile.getS3Key(), e.getMessage());
                return; // Skip oversized files
            } catch (S3FileContentService.FileContentRetrievalException e) {
                log.error("Failed to retrieve current file content from S3, skipping: file={}, key={}, error={}", fileName, currentFile.getS3Key(), e.getMessage());
                return; // Skip files that fail to retrieve
            }

            // T063: Fetch target file content (if exists) with error handling
            String targetContent = null;
            String targetFileName = null;
            if (targetFile != null) {
                try {
                    targetContent = s3FileContentService.fetchFileContent(targetFile.getS3Key());
                    targetFileName = targetFile.getOriginalFileName();
                } catch (S3FileContentService.FileNotFoundException e) {
                    log.warn("Target file not found in S3, treating as new file: file={}, key={}", fileName, targetFile.getS3Key());
                    // Continue - file will be marked as ADDED
                } catch (S3FileContentService.BinaryFileException e) {
                    log.info("Binary target file detected (cannot compare), skipping: file={}, key={}", fileName, targetFile.getS3Key());
                    return; // Skip if target is binary
                } catch (Exception e) {
                    log.error("Failed to retrieve target file content from S3, skipping: file={}, key={}, error={}", fileName, targetFile.getS3Key(), e.getMessage());
                    return; // Skip if target fails to retrieve
                }
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

        } catch (Exception e) {
            // T063: Catch-all for unexpected errors
            log.error("Unexpected error comparing file: file={}, error={}", fileName, e.getMessage(), e);
            // Don't rethrow - continue with next file
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
     * Validates that a batch exists and has files available for comparison.
     *
     * <p><strong>Business Rule Change (2025-11-03):</strong> Removed COMPLETED status requirement.
     * Batches in any status (IN_PROGRESS, COMPLETED, FAILED) can be compared as long as they have files.
     *
     * @param batchId the batch ID to validate
     * @param batchLabel label for error messages ("Current" or "Target")
     * @return the validated batch
     * @throws BatchNotFoundException if batch does not exist
     * @throws IllegalArgumentException if batch has no files
     */
    private Batch validateBatchExistsAndHasFiles(UUID batchId, String batchLabel) {
        Batch batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new BatchNotFoundException(
                batchLabel + " batch " + batchId + " does not exist"
            ));

        // Validate batch has files
        if (batch.getUploadedFilesCount() == 0) {
            throw new IllegalArgumentException(
                batchLabel + " batch " + batchId + " has no files to compare (uploadedFilesCount=0)"
            );
        }

        log.info("{} batch {} validated: status={}, fileCount={}",
            batchLabel, batchId, batch.getStatus(), batch.getUploadedFilesCount());

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
