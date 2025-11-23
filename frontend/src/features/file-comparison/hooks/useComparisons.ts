/**
 * T126: useComparisons query hook
 *
 * TanStack Query hook for fetching paginated comparison list with:
 * - Pagination support (page, size)
 * - Optional status filtering
 * - Automatic caching and refetching
 * - Loading and error states
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * Priority: P3
 *
 * @module features/file-comparison/hooks/useComparisons
 */

import { useQuery, UseQueryOptions } from '@tanstack/react-query';
import { comparisonApi } from '../api/comparisonApi';
import type { PagedComparisonResponse, Comparison } from '@/entities/comparison/model/types';

// Re-export types for backward compatibility
export type { PagedComparisonResponse, Comparison };

/**
 * Parameters for the useComparisons hook
 */
export interface UseComparisonsParams {
  /** Page number (0-indexed) */
  page?: number;
  /** Page size (items per page) */
  size?: number;
  /** Optional status filter (PENDING, COMPLETED, FAILED, IN_PROGRESS) */
  status?: string;
}

/**
 * Query hook for fetching comparison list with pagination and filtering.
 *
 * Features:
 * - Automatic caching with TanStack Query
 * - Pagination support (page, size parameters)
 * - Optional status filtering
 * - Query key includes all parameters for proper cache management
 * - Refetch on window focus and network reconnect
 *
 * @param params - Query parameters (page, size, status)
 * @param options - TanStack Query options (enabled, refetchInterval, etc.)
 * @returns TanStack Query result with comparisons data, loading, error states
 *
 * @example
 * ```tsx
 * function ComparisonList() {
 *   const { data, isLoading, error } = useComparisons({ page: 0, size: 10 });
 *
 *   if (isLoading) return <div>Loading...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   return (
 *     <div>
 *       {data?.content.map(comparison => (
 *         <ComparisonCard key={comparison.id} comparison={comparison} />
 *       ))}
 *     </div>
 *   );
 * }
 * ```
 *
 * @example With status filter
 * ```tsx
 * const { data } = useComparisons({ status: 'COMPLETED', page: 0, size: 20 });
 * ```
 */
export const useComparisons = (
  params: UseComparisonsParams = {},
  options?: Omit<UseQueryOptions<PagedComparisonResponse, Error>, 'queryKey' | 'queryFn'>
) => {
  const { page = 0, size = 10, status } = params;

  return useQuery<PagedComparisonResponse, Error>({
    // Query key includes all parameters for proper caching
    // When parameters change, a new query is executed
    queryKey: ['comparisons', { page, size, status }],

    // Query function calls the API with the provided parameters
    queryFn: () => comparisonApi.listComparisons({ page, size, status }),

    // Stale time: 5 minutes (comparisons don't change frequently)
    staleTime: 5 * 60 * 1000,

    // Cache time: 30 minutes
    gcTime: 30 * 60 * 1000,

    // Merge with any custom options passed by the caller
    ...options,
  });
};
