/**
 * T036: React Query hooks for batch history
 *
 * Provides infinite query hook for cursor-based pagination.
 * Automatically handles cursor management and cache invalidation.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { useInfiniteQuery } from '@tanstack/react-query';
import { listBatches } from './batchApi';

/**
 * Query keys for batch-related queries
 */
export const batchKeys = {
  all: ['batches'] as const,
  history: () => [...batchKeys.all, 'history'] as const,
  historyWithLimit: (limit: number) => [...batchKeys.history(), { limit }] as const,
};

/**
 * T036: Infinite query hook for batch history with cursor pagination.
 *
 * Automatically manages cursor state and fetches next pages.
 * Use with infinite scroll or "Load more" button.
 *
 * @param limit - Items per page (default 20)
 * @returns TanStack Query infinite query result
 *
 * @example
 * ```tsx
 * const { data, fetchNextPage, hasNextPage, isFetchingNextPage } = useBatchHistory(20);
 *
 * // Access all pages
 * const allBatches = data?.pages.flatMap(page => page.items) ?? [];
 *
 * // Load more button
 * <button
 *   onClick={() => fetchNextPage()}
 *   disabled={!hasNextPage || isFetchingNextPage}
 * >
 *   {isFetchingNextPage ? 'Loading...' : 'Load More'}
 * </button>
 * ```
 */
export function useBatchHistory(limit: number = 20) {
  return useInfiniteQuery({
    queryKey: batchKeys.historyWithLimit(limit),

    // Fetch function for each page
    queryFn: async ({ pageParam }) => {
      return listBatches(pageParam as string | undefined, limit);
    },

    // Extract cursor for next page
    getNextPageParam: (lastPage) => {
      return lastPage.hasNext ? lastPage.nextCursor : undefined;
    },

    // Initial page param (undefined = first page)
    initialPageParam: undefined as string | undefined,

    // Stale time: 5 minutes (matches backend cache TTL)
    staleTime: 5 * 60 * 1000,

    // Cache time: 10 minutes
    gcTime: 10 * 60 * 1000,

    // Refetch on window focus for fresh data
    refetchOnWindowFocus: true,
  });
}
