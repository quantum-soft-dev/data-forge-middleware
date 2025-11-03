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

export interface PagedComparisonResponse {
  content: ComparisonResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
