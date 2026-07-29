/**
 * Utility functions for parsing unified diff structures
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T072 - Create parseDiff utility function
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 *
 * @module features/file-comparison/model/parseDiff
 */

import type { UnifiedDiff } from '@/entities/comparison/model/types';

/**
 * Parsed diff format compatible with react-diff-viewer-continued
 */
export interface ParsedDiff {
  oldValue: string;
  newValue: string;
}

/**
 * Converts a UnifiedDiff structure to a format compatible with react-diff-viewer-continued.
 *
 * The library expects two complete text strings representing the old and new file contents.
 * This function reconstructs those strings from the diff hunks.
 *
 * @param unifiedDiff - The unified diff structure from the API
 * @returns ParsedDiff object with oldValue and newValue strings
 *
 * @example
 * ```ts
 * const diff: UnifiedDiff = {
 *   hunks: [{
 *     oldStart: 1,
 *     oldLines: 2,
 *     newStart: 1,
 *     newLines: 3,
 *     changes: [
 *       { type: 'UNCHANGED', lineNumber: 1, content: 'line 1' },
 *       { type: 'REMOVED', lineNumber: 2, content: 'old line' },
 *       { type: 'ADDED', lineNumber: 2, content: 'new line' },
 *       { type: 'ADDED', lineNumber: 3, content: 'added line' },
 *     ]
 *   }],
 *   oldFileName: 'file.txt',
 *   newFileName: 'file.txt'
 * };
 *
 * const parsed = parseDiff(diff);
 * // parsed.oldValue === "line 1\nold line"
 * // parsed.newValue === "line 1\nnew line\nadded line"
 * ```
 */
export function parseDiff(unifiedDiff: UnifiedDiff): ParsedDiff {
  if (!unifiedDiff || !unifiedDiff.hunks || unifiedDiff.hunks.length === 0) {
    return { oldValue: '', newValue: '' };
  }

  const oldLines: string[] = [];
  const newLines: string[] = [];

  // Process each hunk sequentially
  for (const hunk of unifiedDiff.hunks) {
    if (!hunk.changes || hunk.changes.length === 0) {
      continue;
    }

    // Process changes within the hunk
    for (const change of hunk.changes) {
      switch (change.type) {
        case 'UNCHANGED':
          // Unchanged lines appear in both old and new
          oldLines.push(change.content);
          newLines.push(change.content);
          break;

        case 'REMOVED':
          // Removed lines only appear in old
          oldLines.push(change.content);
          break;

        case 'ADDED':
          // Added lines only appear in new
          newLines.push(change.content);
          break;

        default:
          // Handle unexpected change types gracefully
          console.warn(`Unknown change type: ${(change as { type?: string }).type}`);
      }
    }
  }

  return {
    oldValue: oldLines.join('\n'),
    newValue: newLines.join('\n'),
  };
}

/**
 * Checks if a unified diff represents a new file (all additions).
 *
 * @param unifiedDiff - The unified diff structure
 * @returns true if the file is entirely new (no old file)
 *
 * @example
 * ```ts
 * const newFileDiff: UnifiedDiff = {
 *   hunks: [{ changes: [{ type: 'ADDED', lineNumber: 1, content: 'new content' }] }],
 *   oldFileName: null,
 *   newFileName: 'new_file.txt'
 * };
 *
 * isNewFile(newFileDiff); // true
 * ```
 */
export function isNewFile(unifiedDiff: UnifiedDiff): boolean {
  return !unifiedDiff.oldFileName || unifiedDiff.oldFileName === null;
}

/**
 * Checks if a unified diff represents a deleted file (all removals).
 *
 * @param unifiedDiff - The unified diff structure
 * @returns true if the file was deleted (no new file)
 *
 * @example
 * ```ts
 * const deletedFileDiff: UnifiedDiff = {
 *   hunks: [{ changes: [{ type: 'REMOVED', lineNumber: 1, content: 'old content' }] }],
 *   oldFileName: 'deleted_file.txt',
 *   newFileName: null
 * };
 *
 * isDeletedFile(deletedFileDiff); // true
 * ```
 */
export function isDeletedFile(unifiedDiff: UnifiedDiff): boolean {
  return !unifiedDiff.newFileName || unifiedDiff.newFileName === null;
}

/**
 * Calculates diff statistics from a UnifiedDiff structure.
 *
 * @param unifiedDiff - The unified diff structure
 * @returns Object containing counts of additions, deletions, and unchanged lines
 *
 * @example
 * ```ts
 * const diff: UnifiedDiff = { ... };
 * const stats = getDiffStats(diff);
 * // stats === { additions: 5, deletions: 2, unchanged: 10 }
 * ```
 */
export function getDiffStats(unifiedDiff: UnifiedDiff): {
  additions: number;
  deletions: number;
  unchanged: number;
} {
  if (!unifiedDiff || !unifiedDiff.hunks) {
    return { additions: 0, deletions: 0, unchanged: 0 };
  }

  let additions = 0;
  let deletions = 0;
  let unchanged = 0;

  for (const hunk of unifiedDiff.hunks) {
    if (!hunk.changes) continue;

    for (const change of hunk.changes) {
      switch (change.type) {
        case 'ADDED':
          additions++;
          break;
        case 'REMOVED':
          deletions++;
          break;
        case 'UNCHANGED':
          unchanged++;
          break;
      }
    }
  }

  return { additions, deletions, unchanged };
}

/**
 * Generates a human-readable summary of diff changes.
 *
 * @param additions - Number of lines added
 * @param deletions - Number of lines deleted
 * @returns Formatted summary string (e.g., "+5 -2")
 *
 * @example
 * ```ts
 * formatDiffSummary(10, 5); // "+10 -5"
 * formatDiffSummary(0, 3);  // "-3"
 * formatDiffSummary(5, 0);  // "+5"
 * ```
 */
export function formatDiffSummary(additions: number, deletions: number): string {
  if (additions === 0 && deletions === 0) {
    return 'No changes';
  }
  if (deletions === 0) {
    return `+${additions}`;
  }
  if (additions === 0) {
    return `-${deletions}`;
  }
  return `+${additions} -${deletions}`;
}
