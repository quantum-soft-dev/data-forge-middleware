package com.bitbi.dfm.comparison.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root representing a comparison operation between two upload sessions (batches).
 *
 * <p>This aggregate manages the lifecycle of a file comparison operation, enforcing
 * invariants and coordinating with {@link ComparisonResult} child entities.
 *
 * <p>Invariants:
 * <ul>
 *   <li>A comparison must reference two valid, different batches</li>
 *   <li>Both batches must belong to the same account</li>
 *   <li>A comparison cannot be deleted while status is IN_PROGRESS</li>
 *   <li>Statistics must be consistent: total = changed + added + unchanged</li>
 *   <li>Status transitions must follow the defined lifecycle</li>
 * </ul>
 *
 * <p>Lifecycle: PENDING → IN_PROGRESS → COMPLETED/FAILED
 */
public class FileComparison {
    private Long id;
    private UUID currentBatchId;
    private UUID targetBatchId;
    private UUID accountId;
    private ComparisonStatus status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Integer totalFilesCompared;
    private Integer filesChanged;
    private Integer filesAdded;
    private Integer filesUnchanged;
    private Long totalChangeSize;
    private String errorMessage;
    private List<ComparisonResult> results;

    /**
     * Default constructor for JPA/frameworks.
     */
    protected FileComparison() {
        this.results = new ArrayList<>();
        this.totalFilesCompared = 0;
        this.filesChanged = 0;
        this.filesAdded = 0;
        this.filesUnchanged = 0;
        this.totalChangeSize = 0L;
    }

    /**
     * Creates a new FileComparison aggregate.
     *
     * @param currentBatchId the current batch ID (source)
     * @param targetBatchId the target batch ID (comparison baseline)
     * @param accountId the account owner ID
     * @throws IllegalArgumentException if validation fails
     */
    public FileComparison(UUID currentBatchId, UUID targetBatchId, UUID accountId) {
        this();
        validateCreationArguments(currentBatchId, targetBatchId, accountId);

        this.currentBatchId = currentBatchId;
        this.targetBatchId = targetBatchId;
        this.accountId = accountId;
        this.status = ComparisonStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * Validates arguments for FileComparison creation.
     *
     * @throws IllegalArgumentException if validation fails
     */
    private void validateCreationArguments(UUID currentBatchId, UUID targetBatchId, UUID accountId) {
        if (currentBatchId == null) {
            throw new IllegalArgumentException("Current batch ID cannot be null");
        }
        if (targetBatchId == null) {
            throw new IllegalArgumentException("Target batch ID cannot be null");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID cannot be null");
        }
        if (currentBatchId.equals(targetBatchId)) {
            throw new IllegalArgumentException(
                "Cannot compare a batch with itself (currentBatchId == targetBatchId)"
            );
        }
    }

    /**
     * Transitions the comparison to IN_PROGRESS status and sets the start time.
     *
     * @throws IllegalStateException if the current status does not allow this transition
     */
    public void startComparison() {
        if (!status.canTransitionTo(ComparisonStatus.IN_PROGRESS)) {
            throw new IllegalStateException(
                "Cannot start comparison from status " + status
            );
        }
        this.status = ComparisonStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
    }

    /**
     * Transitions the comparison to COMPLETED status and sets the completion time.
     *
     * @throws IllegalStateException if the current status does not allow this transition
     */
    public void completeComparison() {
        if (!status.canTransitionTo(ComparisonStatus.COMPLETED)) {
            throw new IllegalStateException(
                "Cannot complete comparison from status " + status
            );
        }
        this.status = ComparisonStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /**
     * Transitions the comparison to FAILED status with an error message.
     *
     * @param errorMessage description of the error that occurred
     * @throws IllegalArgumentException if errorMessage is null or empty
     * @throws IllegalStateException if the current status does not allow this transition
     */
    public void failComparison(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Error message cannot be null or blank");
        }
        if (!status.canTransitionTo(ComparisonStatus.FAILED)) {
            throw new IllegalStateException(
                "Cannot fail comparison from status " + status
            );
        }
        this.status = ComparisonStatus.FAILED;
        this.completedAt = Instant.now();
        this.errorMessage = errorMessage.length() > 1000
            ? errorMessage.substring(0, 1000)
            : errorMessage;
    }

    /**
     * Adds a comparison result and updates aggregate statistics.
     *
     * @param result the comparison result to add
     * @throws IllegalArgumentException if result is null or validation fails
     * @throws IllegalStateException if comparison is not in a valid state to add results
     */
    public void addResult(ComparisonResult result) {
        if (result == null) {
            throw new IllegalArgumentException("ComparisonResult cannot be null");
        }
        if (status != ComparisonStatus.IN_PROGRESS && status != ComparisonStatus.PENDING) {
            throw new IllegalStateException(
                "Cannot add results to comparison with status " + status
            );
        }

        this.results.add(result);
        updateStatistics(result);
    }

    /**
     * Updates aggregate statistics based on a new result.
     *
     * @param result the result to incorporate into statistics
     */
    private void updateStatistics(ComparisonResult result) {
        this.totalFilesCompared++;

        switch (result.getChangeType()) {
            case MODIFIED:
                this.filesChanged++;
                break;
            case ADDED:
                this.filesAdded++;
                break;
            case UNCHANGED:
                this.filesUnchanged++;
                break;
            case REMOVED:
                // Future enhancement: track removed files
                break;
        }

        if (result.getChangeSize() != null) {
            this.totalChangeSize += result.getChangeSize();
        }

        validateStatisticsInvariant();
    }

    /**
     * Validates that statistics satisfy the invariant: total = changed + added + unchanged.
     *
     * @throws IllegalStateException if invariant is violated
     */
    private void validateStatisticsInvariant() {
        if (totalFilesCompared != filesChanged + filesAdded + filesUnchanged) {
            throw new IllegalStateException(
                String.format(
                    "Statistics invariant violated: totalFilesCompared (%d) != filesChanged (%d) + filesAdded (%d) + filesUnchanged (%d)",
                    totalFilesCompared, filesChanged, filesAdded, filesUnchanged
                )
            );
        }
    }

    /**
     * Checks if this comparison can be deleted.
     * Comparisons cannot be deleted while IN_PROGRESS.
     *
     * @return true if deletion is allowed
     */
    public boolean canDelete() {
        return status.allowsDeletion();
    }

    /**
     * Generates a summary value object for this comparison.
     *
     * @return ComparisonSummary value object
     */
    public ComparisonSummary getSummary() {
        return ComparisonSummary.from(this);
    }

    /**
     * Checks if this comparison has any changes.
     *
     * @return true if there are any changed or added files
     */
    public boolean hasChanges() {
        return filesChanged > 0 || filesAdded > 0;
    }

    /**
     * Checks if this comparison is complete (either COMPLETED or FAILED).
     *
     * @return true if status is terminal
     */
    public boolean isComplete() {
        return status.isTerminal();
    }

    // Getters
    public Long getId() {
        return id;
    }

    public UUID getCurrentBatchId() {
        return currentBatchId;
    }

    public UUID getTargetBatchId() {
        return targetBatchId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public ComparisonStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Integer getTotalFilesCompared() {
        return totalFilesCompared;
    }

    public Integer getFilesChanged() {
        return filesChanged;
    }

    public Integer getFilesAdded() {
        return filesAdded;
    }

    public Integer getFilesUnchanged() {
        return filesUnchanged;
    }

    public Long getTotalChangeSize() {
        return totalChangeSize;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns an unmodifiable view of the results list.
     *
     * @return unmodifiable list of comparison results
     */
    public List<ComparisonResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    // Protected setters for JPA
    protected void setId(Long id) {
        this.id = id;
    }

    protected void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    protected void setResults(List<ComparisonResult> results) {
        this.results = results;
    }
}
