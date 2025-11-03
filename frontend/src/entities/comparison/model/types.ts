/**
 * TypeScript types for file comparison entities.
 *
 * These types mirror the backend DTOs and provide type safety for
 * comparison-related data throughout the frontend application.
 *
 * @module entities/comparison/model/types
 */

/**
 * Comparison status enum matching backend ComparisonStatus.
 *
 * Lifecycle: PENDING → IN_PROGRESS → COMPLETED/FAILED
 */
export enum ComparisonStatus {
  PENDING = 'PENDING',
  IN_PROGRESS = 'IN_PROGRESS',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
}

/**
 * Change type enum matching backend ChangeType.
 *
 * Classifies the type of change detected for a file.
 */
export enum ChangeType {
  ADDED = 'ADDED',
  MODIFIED = 'MODIFIED',
  UNCHANGED = 'UNCHANGED',
  REMOVED = 'REMOVED',
}

/**
 * File comparison metadata and statistics.
 *
 * Represents a comparison operation between two upload sessions (batches).
 */
export interface Comparison {
  /** Comparison ID */
  id: number;

  /** Current batch ID (source) */
  currentBatchId: number;

  /** Target batch ID (comparison baseline) */
  targetBatchId: number;

  /** Account owner ID */
  accountId: number;

  /** Comparison status */
  status: ComparisonStatus;

  /** Creation timestamp (ISO 8601) */
  createdAt: string;

  /** Start timestamp (ISO 8601, nullable) */
  startedAt: string | null;

  /** Completion timestamp (ISO 8601, nullable) */
  completedAt: string | null;

  /** Total number of files compared */
  totalFilesCompared: number;

  /** Number of files with changes */
  filesChanged: number;

  /** Number of new files */
  filesAdded: number;

  /** Number of unchanged files */
  filesUnchanged: number;

  /** Total change size in bytes */
  totalChangeSize: number;

  /** Error message if status is FAILED (nullable) */
  errorMessage: string | null;
}

/**
 * Individual file comparison result with diff details.
 *
 * Represents the diff result for a single file within a comparison.
 */
export interface ComparisonResult {
  /** Result ID */
  id: number;

  /** Parent comparison ID */
  comparisonId: number;

  /** Current file ID */
  fileId: number;

  /** Target file ID (null for new files) */
  targetFileId: number | null;

  /** Current file name */
  fileName: string;

  /** Target file name (null for new files) */
  targetFileName: string | null;

  /** Change type */
  changeType: ChangeType;

  /** Number of lines added */
  lineAdditions: number;

  /** Number of lines deleted */
  lineDeletions: number;

  /** Change size in bytes */
  changeSize: number;

  /** Unified diff in structured JSON format (nullable for unchanged files) */
  unifiedDiff: UnifiedDiff | null;

  /** Creation timestamp (ISO 8601) */
  createdAt: string;
}

/**
 * Structured unified diff format compatible with react-diff-viewer-continued.
 *
 * This structure represents the diff output in a format that can be
 * easily rendered by diff viewer components.
 */
export interface UnifiedDiff {
  /** Array of diff hunks (changes grouped by location) */
  hunks: DiffHunk[];

  /** Old file name */
  oldFileName: string;

  /** New file name */
  newFileName: string;
}

/**
 * A hunk represents a contiguous block of changes in a diff.
 *
 * Each hunk contains the line numbers and the actual line changes.
 */
export interface DiffHunk {
  /** Starting line number in old file (1-based) */
  oldStart: number;

  /** Number of lines in old file */
  oldLines: number;

  /** Starting line number in new file (1-based) */
  newStart: number;

  /** Number of lines in new file */
  newLines: number;

  /** Array of individual line changes */
  changes: DiffChange[];
}

/**
 * A single line change within a diff hunk.
 */
export interface DiffChange {
  /** Type of change for this line */
  type: 'ADDED' | 'REMOVED' | 'UNCHANGED';

  /** Line number (context-dependent on type) */
  lineNumber: number;

  /** Line content */
  content: string;
}

/**
 * Comparison summary statistics.
 *
 * Provides a high-level overview of comparison results.
 */
export interface ComparisonSummary {
  /** Total files compared */
  totalFilesCompared: number;

  /** Files with changes */
  filesChanged: number;

  /** New files */
  filesAdded: number;

  /** Unchanged files */
  filesUnchanged: number;

  /** Total change size in bytes */
  totalChangeSize: number;

  /** Comparison completion timestamp (ISO 8601) */
  comparisonTimestamp: string;

  /** Current batch ID */
  currentBatchId: number;

  /** Target batch ID */
  targetBatchId: number;
}

/**
 * Request payload for creating a new comparison.
 */
export interface CreateComparisonRequest {
  /** Current batch ID (source) */
  currentBatchId: number;

  /** Target batch ID (comparison baseline) */
  targetBatchId: number;

  /** Optional list of file IDs to compare (null = compare all files) */
  fileIds?: number[] | null;
}

/**
 * Paginated response wrapper for comparison listings.
 */
export interface PagedComparisonResponse {
  /** List of comparisons in the current page */
  content: Comparison[];

  /** Current page number (0-indexed) */
  page: number;

  /** Page size */
  size: number;

  /** Total number of comparisons across all pages */
  totalElements: number;

  /** Total number of pages */
  totalPages: number;
}

/**
 * Paginated response wrapper for comparison result listings.
 */
export interface PagedComparisonResultResponse {
  /** List of comparison results in the current page */
  content: ComparisonResult[];

  /** Current page number (0-indexed) */
  page: number;

  /** Page size */
  size: number;

  /** Total number of results across all pages */
  totalElements: number;

  /** Total number of pages */
  totalPages: number;
}

/**
 * Helper type guards for type narrowing.
 */
export const isComparisonComplete = (comparison: Comparison): boolean => {
  return (
    comparison.status === ComparisonStatus.COMPLETED ||
    comparison.status === ComparisonStatus.FAILED
  );
};

export const isComparisonInProgress = (comparison: Comparison): boolean => {
  return comparison.status === ComparisonStatus.IN_PROGRESS;
};

export const hasChanges = (comparison: Comparison): boolean => {
  return comparison.filesChanged > 0 || comparison.filesAdded > 0;
};

export const getDiffSummary = (result: ComparisonResult): string => {
  if (result.changeType === ChangeType.UNCHANGED) {
    return 'No changes';
  }
  return `+${result.lineAdditions} -${result.lineDeletions}`;
};
