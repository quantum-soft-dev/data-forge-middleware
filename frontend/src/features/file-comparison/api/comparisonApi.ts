/**
 * API client for file comparison operations.
 *
 * This module provides typed HTTP methods for all comparison-related
 * endpoints, with automatic request/response validation using Zod schemas.
 *
 * @module features/file-comparison/api/comparisonApi
 */

import { apiClient } from '@/shared/api/client';
import {
  COMPARISONS,
  COMPARISONS_ID,
  COMPARISONS_RESULTS,
  COMPARISONS_SUMMARY,
  COMPARISONS_DOWNLOAD,
  COMPARISONS_SUMMARY_DOWNLOAD
} from '@/shared/api/apiRoutes';
import type {
  Comparison,
  ComparisonSummary,
  CreateComparisonRequest,
  PagedComparisonResponse,
  PagedComparisonResultResponse,
} from '@/entities/comparison/model/types';
import {
  comparisonSchema,
  comparisonSummarySchema,
  pagedComparisonResponseSchema,
  pagedComparisonResultResponseSchema,
} from '../model/schemas';

/**
 * API client for file comparison operations.
 */
export const comparisonApi = {
  /**
   * Creates a new file comparison between two upload sessions.
   *
   * @param request - The comparison creation request
   * @returns Promise resolving to the created comparison
   * @throws {AxiosError} If the request fails
   *
   * @example
   * ```typescript
   * const comparison = await comparisonApi.createComparison({
   *   currentBatchId: 123,
   *   targetBatchId: 120,
   *   fileIds: [501, 502, 503], // Optional: null = compare all files
   * });
   * console.log(`Comparison created: ${comparison.id}`);
   * ```
   */
  createComparison: async (request: CreateComparisonRequest): Promise<Comparison> => {
    const response = await apiClient.post<Comparison>(COMPARISONS, request);
    return comparisonSchema.parse(response.data);
  },

  /**
   * Retrieves a comparison by ID.
   *
   * @param comparisonId - The comparison ID
   * @returns Promise resolving to the comparison
   * @throws {AxiosError} If the request fails or comparison not found
   *
   * @example
   * ```typescript
   * const comparison = await comparisonApi.getComparison(45);
   * console.log(`Status: ${comparison.status}`);
   * ```
   */
  getComparison: async (comparisonId: number): Promise<Comparison> => {
    const response = await apiClient.get<Comparison>(COMPARISONS_ID(comparisonId));
    return comparisonSchema.parse(response.data);
  },

  /**
   * Lists comparisons for the authenticated user with pagination.
   *
   * @param params - Query parameters for filtering and pagination
   * @returns Promise resolving to paginated comparison list
   * @throws {AxiosError} If the request fails
   *
   * @example
   * ```typescript
   * const page = await comparisonApi.listComparisons({
   *   page: 0,
   *   size: 20,
   *   status: 'COMPLETED',
   * });
   * console.log(`Total: ${page.totalElements} comparisons`);
   * ```
   */
  listComparisons: async (params?: {
    page?: number;
    size?: number;
    status?: string;
  }): Promise<PagedComparisonResponse> => {
    const response = await apiClient.get<PagedComparisonResponse>(COMPARISONS, { params });
    return pagedComparisonResponseSchema.parse(response.data);
  },

  /**
   * Retrieves comparison results (individual file diffs) with pagination.
   *
   * @param comparisonId - The comparison ID
   * @param params - Query parameters for filtering and pagination
   * @returns Promise resolving to paginated comparison result list
   * @throws {AxiosError} If the request fails
   *
   * @example
   * ```typescript
   * const results = await comparisonApi.getComparisonResults(45, {
   *   changeType: 'MODIFIED',
   *   page: 0,
   *   size: 50,
   * });
   * console.log(`Found ${results.totalElements} modified files`);
   * ```
   */
  getComparisonResults: async (
    comparisonId: number,
    params?: {
      changeType?: string;
      page?: number;
      size?: number;
    }
  ): Promise<PagedComparisonResultResponse> => {
    const response = await apiClient.get<PagedComparisonResultResponse>(
      COMPARISONS_RESULTS(comparisonId),
      { params }
    );
    return pagedComparisonResultResponseSchema.parse(response.data);
  },

  /**
   * Retrieves a comparison summary with statistics.
   *
   * @param comparisonId - The comparison ID
   * @returns Promise resolving to the comparison summary
   * @throws {AxiosError} If the request fails or comparison not completed
   *
   * @example
   * ```typescript
   * const summary = await comparisonApi.getComparisonSummary(45);
   * console.log(`Changes: ${summary.filesChanged}, Added: ${summary.filesAdded}`);
   * ```
   */
  getComparisonSummary: async (comparisonId: number): Promise<ComparisonSummary> => {
    const response = await apiClient.get<ComparisonSummary>(
      COMPARISONS_SUMMARY(comparisonId)
    );
    return comparisonSummarySchema.parse(response.data);
  },

  /**
   * Deletes a comparison and all its results.
   *
   * @param comparisonId - The comparison ID
   * @returns Promise resolving when deletion is complete
   * @throws {AxiosError} If the request fails or comparison is IN_PROGRESS
   *
   * @example
   * ```typescript
   * await comparisonApi.deleteComparison(45);
   * console.log('Comparison deleted successfully');
   * ```
   */
  deleteComparison: async (comparisonId: number): Promise<void> => {
    await apiClient.delete(COMPARISONS_ID(comparisonId));
  },

  /**
   * Downloads comparison results as a ZIP archive.
   *
   * @param comparisonId - The ID of the comparison to download
   * @returns Promise resolving to axios response with Blob data and headers
   * @throws {AxiosError} If the request fails
   *
   * @example
   * ```typescript
   * const response = await comparisonApi.downloadComparison(123);
   * const blob = response.data;
   * const filename = response.headers['content-disposition']
   *   .split('filename=')[1]
   *   .replace(/"/g, '');
   * // Create download link
   * const url = URL.createObjectURL(blob);
   * const link = document.createElement('a');
   * link.href = url;
   * link.download = filename;
   * link.click();
   * URL.revokeObjectURL(url);
   * ```
   *
   * Phase 7 (User Story 4) - Task T095
   */
  downloadComparison: async (comparisonId: string) => {
    const response = await apiClient.get(COMPARISONS_DOWNLOAD(Number(comparisonId)), {
      responseType: 'blob', // Important: Request binary data
    });
    return response;
  },

  /**
   * Download comparison summary report as text file.
   * <p>
   * T103: API client method for downloading summary report.
   * <p>
   * Returns the summary report as a plain text file with Content-Disposition header.
   * The response includes:
   * - Blob data (text/plain content type)
   * - Content-Disposition header with filename: comparison-{id}-summary.txt
   *
   * @param comparisonId - Comparison ID (can be string or number)
   * @returns Promise resolving to AxiosResponse with Blob data and headers
   * @throws Error if comparison not found or user lacks permission
   *
   * @example
   * ```typescript
   * const response = await comparisonApi.downloadSummaryReport('123');
   * const blob = response.data;
   * const filename = response.headers['content-disposition']
   *   .split('filename=')[1]
   *   .replace(/"/g, '');
   * // Create download link
   * const url = URL.createObjectURL(blob);
   * const link = document.createElement('a');
   * link.href = url;
   * link.download = filename;
   * link.click();
   * URL.revokeObjectURL(url);
   * ```
   *
   * Phase 8 (User Story 6) - Task T103
   */
  downloadSummaryReport: async (comparisonId: string) => {
    const response = await apiClient.get(COMPARISONS_SUMMARY_DOWNLOAD(Number(comparisonId)), {
      responseType: 'blob', // Important: Request binary data (text file)
    });
    return response;
  },
};

/**
 * Type-safe API client export for use with TanStack Query.
 *
 * @example
 * ```typescript
 * import { comparisonApi } from '@/features/file-comparison/api/comparisonApi';
 * import { useQuery } from '@tanstack/react-query';
 *
 * function MyComponent() {
 *   const { data, isLoading } = useQuery({
 *     queryKey: ['comparisons', 45],
 *     queryFn: () => comparisonApi.getComparison(45),
 *   });
 *
 *   if (isLoading) return <div>Loading...</div>;
 *   return <div>Status: {data?.status}</div>;
 * }
 * ```
 */
export default comparisonApi;
