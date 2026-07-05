/**
 * Batch entity types for Upload History feature
 *
 * Matches backend DTOs:
 * - BatchSummaryDto
 * - BatchDetailDto
 * - FileMetadataDto
 * - CursorPageResponseDto<T>
 */

/**
 * Batch status enum matching backend BatchStatus
 */
export type BatchStatus =
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'COMPLETED_WITH_WARNINGS'
  | 'NOT_COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

/**
 * Summary information for batch list view
 *
 * Matches: BatchSummaryDto.java
 */
export interface BatchSummary {
  /** Batch unique identifier */
  id: string;
  /** Site identifier */
  siteId: string;
  /** Batch status */
  status: BatchStatus;
  /** Whether batch has any errors (red cross indicator) */
  hasErrors: boolean;
  /** Number of files uploaded */
  uploadedFilesCount: number;
  /** Total size of all files in bytes */
  totalSize: number;
  /** Batch start timestamp (ISO-8601) */
  startedAt: string;
  /** Batch completion timestamp (ISO-8601, null if in progress) */
  completedAt: string | null;
  /** Total records changed in the Delta v2 session (null/absent for v1 file-based batches) */
  deltaRecordCount?: number | null;
  /** Number of tables touched in the Delta v2 session (null/absent for v1 file-based batches) */
  deltaTableCount?: number | null;
}

/**
 * Per-table insert/update/delete counts for a Delta v2 session
 *
 * Matches: DeltaTableStatsDto.java
 */
export interface DeltaTableStat {
  /** Table name */
  table: string;
  /** Rows inserted */
  inserts: number;
  /** Rows updated */
  updates: number;
  /** Rows deleted */
  deletes: number;
}

/**
 * File metadata for file list view
 *
 * Matches: FileMetadataDto.java
 */
export interface FileMetadata {
  /** File unique identifier for download requests */
  id: string;
  /** Original filename as uploaded (e.g., "sales-2024.csv.gz") */
  originalFileName: string;
  /** File size in bytes (convert to KB/MB for display) */
  fileSize: number;
  /** Upload timestamp (ISO-8601) */
  uploadedAt: string;
}

/**
 * Detailed batch information with file list
 *
 * Matches: BatchDetailDto.java
 */
export interface BatchDetail {
  /** Batch unique identifier */
  id: string;
  /** Site identifier */
  siteId: string;
  /** Batch status */
  status: BatchStatus;
  /** Whether batch has any errors */
  hasErrors: boolean;
  /** Number of files uploaded */
  uploadedFilesCount: number;
  /** Total size of all files in bytes */
  totalSize: number;
  /** Batch start timestamp (ISO-8601) */
  startedAt: string;
  /** Batch completion timestamp (ISO-8601, null if in progress) */
  completedAt: string | null;
  /** List of file metadata */
  files: FileMetadata[];
  /** Per-table insert/update/delete counts for Delta v2 sessions (empty for v1 file-based batches) */
  deltaStats?: DeltaTableStat[];
  /** Delta session mode (DELTA | CONTINUOUS | FULL_SNAPSHOT); null/absent for v1 batches (B9). */
  mode?: string | null;
  /** Sequence range covered by the Delta session; null/absent for v1 batches (B9). */
  seqRange?: { first: number; last: number } | null;
}

/**
 * Generic cursor-based pagination wrapper
 *
 * Cursor format: "{startedAt}_{id}" (e.g., "2025-11-01T10:30:00_550e8400-...")
 *
 * Matches: CursorPageResponseDto.java
 */
export interface CursorPageResponse<T> {
  /** Current page items */
  items: T[];
  /** Cursor for next page (null if last page) */
  nextCursor: string | null;
  /** True if more pages exist */
  hasNext: boolean;
}

/**
 * Generic offset-based pagination wrapper
 *
 * Matches: PageResponseDto.java
 */
export interface PageResponse<T> {
  /** Current page items */
  content: T[];
  /** Current page number (0-indexed) */
  page: number;
  /** Page size */
  size: number;
  /** Total number of elements across all pages */
  totalElements: number;
  /** Total number of pages */
  totalPages: number;
}

/**
 * File download response with presigned URL
 *
 * Matches: FileDownloadResponseDto.java
 */
export interface FileDownloadResponse {
  /** Presigned S3 URL for download (15-minute expiry) */
  downloadUrl: string;
  /** Suggested filename for browser download */
  fileName: string;
  /** File size in bytes */
  fileSize: number;
  /** URL expiration timestamp (ISO-8601) */
  expiresAt: string;
}

/**
 * Error summary for error details view
 *
 * Matches: ErrorLogSummaryDto.java
 */
export interface ErrorSummary {
  /** Error log unique identifier */
  id: string;
  /** Batch identifier (may be null) */
  batchId: string | null;
  /** Site identifier */
  siteId: string;
  /** Error type (e.g., "UPLOAD_ERROR", "VALIDATION_ERROR") */
  type: string;
  /** Error title */
  title: string;
  /** Error message */
  message: string;
  /** Stack trace (may be null) */
  stackTrace: string | null;
  /** Client version string (may be null) */
  clientVersion: string | null;
  /** Additional metadata as JSON */
  metadata: Record<string, unknown>;
  /** Error occurrence timestamp (ISO-8601) */
  occurredAt: string;
}

/**
 * Batch cleanup request (admin).
 */
export interface BatchCleanupRequest {
  siteId?: string;
  accountId?: string;
  retentionDays?: number;
  olderThan?: string;
  limit?: number;
  dryRun?: boolean;
}

/**
 * Batch cleanup result summary (admin).
 */
export interface BatchCleanupResult {
  candidates: number;
  deletedBatches: number;
  deletedFiles: number;
  deletedBytes: number;
  errors: string[];
}
