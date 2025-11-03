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
    '/api/v1/comparisons',
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
    `/api/v1/comparisons?${params.toString()}`
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
    `/api/v1/comparisons/${comparisonId}`
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
  await apiClient.delete(`/api/v1/comparisons/${comparisonId}`);
}
