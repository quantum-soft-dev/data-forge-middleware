/**
 * Hook for fetching comparison history for a specific batch.
 *
 * This hook provides:
 * - Automatic fetching of all comparisons for a batch
 * - Loading, error, and data states
 * - Caching with TanStack Query
 *
 * @module features/file-comparison/hooks/useBatchComparisons
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Added: 2025-11-03 - Show comparison history on batch detail page
 */

import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { getComparisonsForBatch } from '@/entities/comparison/api/comparisonApi';
import type { ComparisonResponse } from '@/entities/comparison/model/types';
import { getServerErrorStatus } from '@/shared/api/error-handler';

/**
 * Options for the useBatchComparisons hook.
 */
export interface UseBatchComparisonsOptions {
  /**
   * The batch ID to fetch comparisons for.
   */
  batchId: string;

  /**
   * Whether to enable the query.
   * Useful for conditional fetching.
   * @default true
   */
  enabled?: boolean;
}

/**
 * Hook for fetching all comparisons for a specific batch.
 *
 * Features:
 * - Fetches comparisons where batch is either current or target
 * - Caches result for 1 minute (comparisons don't change frequently)
 * - Handles loading and error states
 * - Auto-refetches on window focus
 *
 * @param options - Configuration options
 * @returns TanStack Query result with comparisons array
 *
 * @example
 * ```tsx
 * function BatchDetailView({ batchId }: { batchId: string }) {
 *   const { data: comparisons, isLoading } = useBatchComparisons({ batchId });
 *
 *   if (isLoading) return <div>Loading comparison history...</div>;
 *
 *   return (
 *     <div>
 *       <h2>Comparison History ({comparisons?.length ?? 0})</h2>
 *       {comparisons?.map(comparison => (
 *         <ComparisonCard key={comparison.id} comparison={comparison} />
 *       ))}
 *     </div>
 *   );
 * }
 * ```
 */
export function useBatchComparisons({
  batchId,
  enabled = true,
}: UseBatchComparisonsOptions): UseQueryResult<ComparisonResponse[], Error> {
  return useQuery<ComparisonResponse[], Error>({
    queryKey: ['comparisons', 'batch', batchId],
    queryFn: () => getComparisonsForBatch(batchId),
    enabled: enabled && !!batchId,

    // Caching configuration
    staleTime: 60000, // Cache for 1 minute
    gcTime: 300000, // Keep in cache for 5 minutes

    // Refetch on window focus
    refetchOnWindowFocus: true,

    // Retry configuration
    retry: (failureCount, error) => {
      // Don't retry on 404 (batch not found) or 403 (unauthorized)
      const status = getServerErrorStatus(error);
      if (status === 404 || status === 403) {
        return false;
      }

      // Retry up to 2 times for other errors
      return failureCount < 2;
    },

    // Retry delay with exponential backoff
    retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 10000),
  });
}

/**
 * Type-safe export for use in components.
 *
 * @example
 * ```tsx
 * import { useBatchComparisons } from '@/features/file-comparison/hooks/useBatchComparisons';
 * ```
 */
export default useBatchComparisons;
