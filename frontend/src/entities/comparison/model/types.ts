/**
 * TypeScript types for file comparison entities
 */
export type ComparisonStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';

export interface CreateComparisonRequest {
  currentBatchId: string;
  targetBatchId: string;
  fileIds?: string[] | null;
}

export interface ComparisonResponse {
  id: number;
  currentBatchId: string;
  targetBatchId: string;
  accountId: string;
  status: ComparisonStatus;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  totalFilesCompared: number;
  filesChanged: number;
  filesAdded: number;
  filesUnchanged: number;
  totalChangeSize: number;
  errorMessage?: string | null;
}

// Alias for backward compatibility
export type Comparison = ComparisonResponse;

export interface PagedComparisonResponse {
  content: ComparisonResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * Change type for file differences
 */
export type ChangeType = 'ADDED' | 'MODIFIED' | 'UNCHANGED' | 'REMOVED';

/**
 * Individual change within a hunk
 */
export interface DiffChange {
  type: 'UNCHANGED' | 'REMOVED' | 'ADDED';
  lineNumber: number;
  content: string;
}

/**
 * A hunk represents a contiguous block of changes in a diff
 */
export interface DiffHunk {
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  changes: DiffChange[];
}

/**
 * Unified diff structure stored in JSONB
 */
export interface UnifiedDiff {
  hunks: DiffHunk[];
  oldFileName: string | null;
  newFileName: string | null;
}

/**
 * Comparison result for a single file
 */
export interface ComparisonResult {
  id: number;
  comparisonId: number;
  fileId: string;
  targetFileId: string | null;
  fileName: string | null; // Can be null (backend TODO in Phase 3)
  targetFileName: string | null;
  changeType: ChangeType;
  lineAdditions: number;
  lineDeletions: number;
  changeSize: number;
  createdAt: string;
  unifiedDiff: string | null; // Backend returns JSON string, needs parsing
}

/**
 * Paginated comparison results response
 */
export interface PagedComparisonResultResponse {
  content: ComparisonResult[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * Comparison summary statistics
 */
export interface ComparisonSummary {
  totalFilesCompared: number;
  filesChanged: number;
  filesAdded: number;
  filesUnchanged: number;
  totalChangeSize: number;
  comparisonTimestamp: string;
  currentBatchId: string;
  targetBatchId: string;
}
