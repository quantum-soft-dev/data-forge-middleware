/**
 * API client for file comparison operations
 *
 * Feature: 009-markdown-user-story (File Diff Comparison)
 * Phase: Phase 3 - User Story 1 (Select Files for Comparison)
 */

import { apiClient } from '@/shared/api/client';
import type {
  CreateComparisonRequest,
  ComparisonResponse,
  PagedComparisonResponse,
  PagedComparisonResultResponse,
  ComparisonSummary,
  ChangeType,
} from '../model/types';

/**
 * Creates a new file comparison between two batches
 *
 * @param request - Comparison request with batch IDs and optional file IDs
 * @returns Promise with comparison metadata
 * @throws Error if API request fails
 */
export async function createComparison(
  request: CreateComparisonRequest
): Promise<ComparisonResponse> {
  const response = await apiClient.post<ComparisonResponse>(
    '/v1/comparisons',
    request
  );
  return response.data;
}

/**
 * Lists all comparisons for the authenticated user
 *
 * @param page - Page number (zero-indexed)
 * @param size - Page size
 * @param status - Optional status filter
 * @returns Promise with paginated comparison list
 */
export async function listComparisons(
  page = 0,
  size = 20,
  status?: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
): Promise<PagedComparisonResponse> {
  const params = new URLSearchParams({
    page: page.toString(),
    size: size.toString(),
  });

  if (status) {
    params.append('status', status);
  }

  const response = await apiClient.get<PagedComparisonResponse>(
    `/v1/comparisons?${params.toString()}`
  );
  return response.data;
}

/**
 * Gets comparison details by ID
 *
 * @param comparisonId - Comparison ID
 * @returns Promise with comparison metadata
 */
export async function getComparison(
  comparisonId: number
): Promise<ComparisonResponse> {
  const response = await apiClient.get<ComparisonResponse>(
    `/v1/comparisons/${comparisonId}`
  );
  return response.data;
}

/**
 * Gets all comparisons for a specific batch
 *
 * <p>Added 2025-11-03: Shows comparison history on batch detail page.
 * <p>Path updated 2025-11-03: Changed from /batch/{batchId} to /by-batch/{batchId}
 * to avoid routing conflicts with /comparisons/{comparisonId}.
 *
 * @param batchId - Batch ID (UUID string)
 * @returns Promise with list of comparisons for this batch
 */
export async function getComparisonsForBatch(
  batchId: string
): Promise<ComparisonResponse[]> {
  const response = await apiClient.get<ComparisonResponse[]>(
    `/v1/comparisons/by-batch/${batchId}`
  );
  return response.data;
}

/**
 * Deletes a comparison
 *
 * @param comparisonId - Comparison ID to delete
 * @returns Promise that resolves when deletion is complete
 */
export async function deleteComparison(
  comparisonId: number
): Promise<void> {
  await apiClient.delete(`/v1/comparisons/${comparisonId}`);
}

/**
 * Gets comparison results with optional change type filter
 *
 * @param comparisonId - Comparison ID
 * @param options - Query options (page, size, changeType)
 * @returns Promise with paginated comparison results
 */
export async function getComparisonResults(
  comparisonId: number,
  options?: {
    page?: number;
    size?: number;
    changeType?: ChangeType;
  }
): Promise<PagedComparisonResultResponse> {
  const page = options?.page ?? 0;
  const size = options?.size ?? 50;
  const changeType = options?.changeType;

  const params = new URLSearchParams({
    page: page.toString(),
    size: size.toString(),
  });

  if (changeType) {
    params.append('changeType', changeType);
  }

  const response = await apiClient.get<PagedComparisonResultResponse>(
    `/v1/comparisons/${comparisonId}/results?${params.toString()}`
  );
  return response.data;
}

/**
 * Gets comparison summary statistics
 *
 * @param comparisonId - Comparison ID
 * @returns Promise with comparison summary
 */
export async function getComparisonSummary(
  comparisonId: number
): Promise<ComparisonSummary> {
  const response = await apiClient.get<ComparisonSummary>(
    `/v1/comparisons/${comparisonId}/summary`
  );
  return response.data;
}
