/**
 * Hook for fetching comparison details with automatic polling for IN_PROGRESS status.
 *
 * This hook provides:
 * - Automatic fetching of comparison details by ID
 * - Polling every 3 seconds while comparison is IN_PROGRESS
 * - Automatic stop of polling when comparison completes or fails
 * - Loading, error, and data states
 *
 * @module features/file-comparison/hooks/useComparisonDetails
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T064 - Create useComparisonDetails hook with polling
 * Phase: Phase 4 - User Story 2 (Compare Files)
 */

import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { comparisonApi } from '../api/comparisonApi';
import type { Comparison } from '@/entities/comparison/model/types';
import { getServerErrorStatus } from '@/shared/api/error-handler';

/**
 * Options for the useComparisonDetails hook.
 */
export interface UseComparisonDetailsOptions {
  /**
   * The comparison ID to fetch.
   */
  comparisonId: number;

  /**
   * Whether to enable polling for IN_PROGRESS comparisons.
   * @default true
   */
  enablePolling?: boolean;

  /**
   * Polling interval in milliseconds.
   * @default 3000 (3 seconds)
   */
  pollingInterval?: number;

  /**
   * Whether to enable the query.
   * Useful for conditional fetching.
   * @default true
   */
  enabled?: boolean;
}

/**
 * Hook for fetching comparison details with automatic polling.
 *
 * Features:
 * - Fetches comparison by ID
 * - Automatically polls every 3 seconds while status is IN_PROGRESS
 * - Stops polling when comparison completes (COMPLETED or FAILED)
 * - Caches result for 30 seconds after completion
 * - Handles loading and error states
 *
 * @param options - Configuration options
 * @returns TanStack Query result with comparison data
 *
 * @example
 * ```tsx
 * function ComparisonDetailPage({ comparisonId }: { comparisonId: number }) {
 *   const { data: comparison, isLoading, error } = useComparisonDetails({
 *     comparisonId,
 *   });
 *
 *   if (isLoading) return <div>Loading comparison...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *   if (!comparison) return null;
 *
 *   return (
 *     <div>
 *       <h1>Comparison {comparison.id}</h1>
 *       <p>Status: {comparison.status}</p>
 *       {comparison.status === 'IN_PROGRESS' && <p>Processing... (auto-updating)</p>}
 *       {comparison.status === 'COMPLETED' && <p>Completed! View results below.</p>}
 *     </div>
 *   );
 * }
 * ```
 *
 * @example With custom polling interval
 * ```tsx
 * const { data } = useComparisonDetails({
 *   comparisonId: 45,
 *   pollingInterval: 5000, // Poll every 5 seconds
 * });
 * ```
 *
 * @example With conditional fetching
 * ```tsx
 * const { data } = useComparisonDetails({
 *   comparisonId: comparisonId ?? 0,
 *   enabled: comparisonId !== null, // Only fetch if ID is available
 * });
 * ```
 */
export function useComparisonDetails({
  comparisonId,
  enablePolling = true,
  pollingInterval = 3000,
  enabled = true,
}: UseComparisonDetailsOptions): UseQueryResult<Comparison, Error> {
  return useQuery<Comparison, Error>({
    queryKey: ['comparison', comparisonId],
    queryFn: () => comparisonApi.getComparison(comparisonId),
    enabled,

    // Polling configuration
    refetchInterval: (query) => {
      // Only poll if enabled and comparison is IN_PROGRESS
      if (!enablePolling) return false;

      const comparison = query.state.data;
      if (!comparison) return false;

      // Poll while IN_PROGRESS, stop when COMPLETED or FAILED
      const shouldPoll = comparison.status === 'IN_PROGRESS';
      return shouldPoll ? pollingInterval : false;
    },

    // Caching configuration
    staleTime: (query) => {
      // For completed/failed comparisons, cache for 30 seconds
      // For in-progress comparisons, always refetch
      const comparison = query.state.data;
      if (!comparison) return 0;

      return comparison.status === 'IN_PROGRESS' ? 0 : 30000;
    },

    // Refetch on window focus only if IN_PROGRESS
    refetchOnWindowFocus: (query) => {
      const comparison = query.state.data;
      return comparison?.status === 'IN_PROGRESS';
    },

    // Retry configuration
    retry: (failureCount, error) => {
      // Don't retry on 404 (comparison not found)
      if (getServerErrorStatus(error) === 404) {
        return false;
      }

      // Retry up to 3 times for other errors
      return failureCount < 3;
    },

    // Retry delay with exponential backoff
    retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000),
  });
}

/**
 * Type-safe export for use in components.
 *
 * @example
 * ```tsx
 * import { useComparisonDetails } from '@/features/file-comparison/hooks/useComparisonDetails';
 * ```
 */
export default useComparisonDetails;
