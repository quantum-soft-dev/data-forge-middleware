package com.bitbi.dfm.comparison.domain;

import java.time.Instant;

/**
 * Domain entity representing the diff result for a single file comparison.
 *
 * <p>This entity represents the comparison outcome for one file pair (current file vs target file)
 * within a {@link FileComparison} aggregate. It stores the diff output, change statistics,
 * and metadata about the comparison.
 *
 * <p>Business rules:
 * <ul>
 *   <li>If changeType = ADDED, targetFileId must be null</li>
 *   <li>If changeType = MODIFIED or UNCHANGED, targetFileId must not be null</li>
 *   <li>If changeType = UNCHANGED, unifiedDiff should be empty or null (no diff stored)</li>
 *   <li>lineAdditions and lineDeletions must be >= 0</li>
 * </ul>
 */
public class ComparisonResult {
    private Long id;
    private Long comparisonId;
    private Long fileId;
    private Long targetFileId;
    private ChangeType changeType;
    private String unifiedDiff;  // JSONB structure as String
    private Integer lineAdditions;
    private Integer lineDeletions;
    private Long changeSize;
    private Instant createdAt;

    /**
     * Default constructor for JPA/frameworks.
     */
    protected ComparisonResult() {
    }

    /**
     * Creates a new ComparisonResult.
     *
     * @param comparisonId the parent comparison ID
     * @param fileId the current file ID
     * @param targetFileId the target file ID (null for ADDED files)
     * @param changeType the type of change detected
     * @param unifiedDiff the diff output in unified format (JSON structure)
     * @param lineAdditions number of lines added
     * @param lineDeletions number of lines deleted
     * @param changeSize total size of changes in bytes
     */
    public ComparisonResult(
        Long comparisonId,
        Long fileId,
        Long targetFileId,
        ChangeType changeType,
        String unifiedDiff,
        Integer lineAdditions,
        Integer lineDeletions,
        Long changeSize
    ) {
        validateBusinessRules(changeType, targetFileId, lineAdditions, lineDeletions);

        this.comparisonId = comparisonId;
        this.fileId = fileId;
        this.targetFileId = targetFileId;
        this.changeType = changeType;
        this.unifiedDiff = unifiedDiff;
        this.lineAdditions = lineAdditions;
        this.lineDeletions = lineDeletions;
        this.changeSize = changeSize;
        this.createdAt = Instant.now();
    }

    /**
     * Validates business rules for ComparisonResult creation.
     *
     * @throws IllegalArgumentException if validation fails
     */
    private void validateBusinessRules(
        ChangeType changeType,
        Long targetFileId,
        Integer lineAdditions,
        Integer lineDeletions
    ) {
        if (changeType == null) {
            throw new IllegalArgumentException("ChangeType cannot be null");
        }

        if (changeType == ChangeType.ADDED && targetFileId != null) {
            throw new IllegalArgumentException(
                "ADDED files must not have a target file ID (target file did not exist)"
            );
        }

        if ((changeType == ChangeType.MODIFIED || changeType == ChangeType.UNCHANGED)
            && targetFileId == null) {
            throw new IllegalArgumentException(
                changeType + " files must have a target file ID (file existed in both batches)"
            );
        }

        if (lineAdditions == null || lineAdditions < 0) {
            throw new IllegalArgumentException("Line additions must be >= 0");
        }

        if (lineDeletions == null || lineDeletions < 0) {
            throw new IllegalArgumentException("Line deletions must be >= 0");
        }
    }

    /**
     * Checks if this result represents a change (not unchanged).
     *
     * @return true if changeType is MODIFIED or ADDED
     */
    public boolean hasChanges() {
        return changeType.countsAsChange();
    }

    /**
     * Gets a human-readable summary of the diff (e.g., "+10 -5").
     *
     * @return diff summary string
     */
    public String getDiffSummary() {
        if (changeType == ChangeType.UNCHANGED) {
            return "No changes";
        }
        return String.format("+%d -%d", lineAdditions, lineDeletions);
    }

    /**
     * Gets the total number of lines changed (additions + deletions).
     *
     * @return total lines changed
     */
    public int getTotalLinesChanged() {
        return lineAdditions + lineDeletions;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getComparisonId() {
        return comparisonId;
    }

    public Long getFileId() {
        return fileId;
    }

    public Long getTargetFileId() {
        return targetFileId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getUnifiedDiff() {
        return unifiedDiff;
    }

    public Integer getLineAdditions() {
        return lineAdditions;
    }

    public Integer getLineDeletions() {
        return lineDeletions;
    }

    public Long getChangeSize() {
        return changeSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // Setters for JPA (protected to prevent external mutation)
    protected void setId(Long id) {
        this.id = id;
    }

    protected void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
