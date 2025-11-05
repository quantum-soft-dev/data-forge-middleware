package com.bitbi.dfm.comparison.domain;

/**
 * Classifies the type of change detected when comparing a file between two upload sessions.
 *
 * <p>Change classification logic:
 * <ul>
 *   <li>ADDED - File exists in current batch but not in target batch (new file)</li>
 *   <li>MODIFIED - File exists in both batches with different content</li>
 *   <li>UNCHANGED - File exists in both batches with identical content</li>
 *   <li>REMOVED - File exists in target batch but not in current batch (future enhancement)</li>
 * </ul>
 */
public enum ChangeType {
    /**
     * File exists in current batch but not in target batch.
     * This represents a newly added file.
     */
    ADDED,

    /**
     * File exists in both batches with different content.
     * This represents a file that has been modified.
     */
    MODIFIED,

    /**
     * File exists in both batches with identical content.
     * No diff is stored for unchanged files to save storage.
     */
    UNCHANGED,

    /**
     * File exists in target batch but not in current batch.
     * This represents a deleted file (future enhancement, not in MVP).
     */
    REMOVED;

    /**
     * Checks if this change type requires a diff to be generated and stored.
     *
     * @return true if diff is required (MODIFIED or ADDED), false otherwise
     */
    public boolean requiresDiff() {
        return this == MODIFIED || this == ADDED;
    }

    /**
     * Checks if this change type counts as an actual change (not unchanged).
     *
     * @return true if this represents a change (MODIFIED, ADDED, or REMOVED)
     */
    public boolean countsAsChange() {
        return this == MODIFIED || this == ADDED || this == REMOVED;
    }

    /**
     * Checks if this change type requires a target file ID.
     * ADDED files have no target (they didn't exist in the target batch).
     *
     * @return true if target file ID is required, false otherwise
     */
    public boolean requiresTargetFile() {
        return this != ADDED;
    }
}
