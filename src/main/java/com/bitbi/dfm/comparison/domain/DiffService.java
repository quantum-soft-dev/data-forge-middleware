package com.bitbi.dfm.comparison.domain;

/**
 * Domain service interface for generating diffs between file contents.
 *
 * <p>This service encapsulates the algorithm for comparing two text files
 * and generating a structured diff output.
 *
 * <p>The implementation is located in the infrastructure layer and uses
 * the java-diff-utils library with the Myers diff algorithm.
 */
public interface DiffService {

    /**
     * Result of a diff operation containing both the structured diff and statistics.
     *
     * @param changeType the type of change detected (ADDED, MODIFIED, UNCHANGED)
     * @param unifiedDiffJson the diff output in JSON format (JSONB structure)
     * @param lineAdditions number of lines added
     * @param lineDeletions number of lines deleted
     * @param changeSize total size of changes in bytes
     */
    record DiffResult(
        ChangeType changeType,
        String unifiedDiffJson,
        int lineAdditions,
        int lineDeletions,
        long changeSize
    ) {}

    /**
     * Generates a diff between two file contents.
     *
     * <p>This method compares the content of two files line-by-line using the Myers diff algorithm
     * and returns a structured diff result with statistics.
     *
     * <p>Special cases:
     * <ul>
     *   <li>If targetContent is null/empty and currentContent exists → ADDED</li>
     *   <li>If both contents are identical → UNCHANGED (no diff stored)</li>
     *   <li>If both contents exist but differ → MODIFIED</li>
     * </ul>
     *
     * @param currentContent the content of the file in the current batch
     * @param targetContent the content of the file in the target batch (null for new files)
     * @param currentFileName the name of the current file (for diff header)
     * @param targetFileName the name of the target file (for diff header, null for new files)
     * @return DiffResult containing change type, diff JSON, and statistics
     * @throws IllegalArgumentException if currentContent is null or empty
     * @throws DiffGenerationException if diff generation fails
     */
    DiffResult generateDiff(
        String currentContent,
        String targetContent,
        String currentFileName,
        String targetFileName
    );

    /**
     * Exception thrown when diff generation fails.
     */
    class DiffGenerationException extends RuntimeException {
        public DiffGenerationException(String message) {
            super(message);
        }

        public DiffGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
