/**
 * T036: React Query hooks for batch history
 *
 * Provides infinite query hook for cursor-based pagination.
 * Automatically handles cursor management and cache invalidation.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { listBatches, getBatchDetails, getBatchErrors } from './batchApi';

/**
 * Query keys for batch-related queries
 */
export const batchKeys = {
  all: ['batches'] as const,
  history: () => [...batchKeys.all, 'history'] as const,
  historyWithLimit: (limit: number) => [...batchKeys.history(), { limit }] as const,
  detail: (batchId: string) => [...batchKeys.all, 'detail', batchId] as const,
  errors: (batchId: string, page: number, size: number) =>
    [...batchKeys.all, 'errors', batchId, { page, size }] as const,
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

/**
 * T053: Query hook for batch details with file list.
 *
 * Fetches detailed batch information including all uploaded files.
 * Cached for 30 minutes (matches backend cache TTL for completed batches).
 *
 * @param batchId - Batch unique identifier (UUID)
 * @returns TanStack Query result with batch details
 *
 * @example
 * ```tsx
 * const { data, isLoading, error } = useBatchDetails(batchId);
 *
 * if (isLoading) return <Spinner />;
 * if (error) return <ErrorAlert message={error.message} />;
 *
 * return (
 *   <div>
 *     <h2>Batch {data.id}</h2>
 *     <p>Status: {data.status}</p>
 *     <p>Files: {data.files.length}</p>
 *   </div>
 * );
 * ```
 */
export function useBatchDetails(batchId: string) {
  return useQuery({
    queryKey: batchKeys.detail(batchId),

    // Fetch function
    queryFn: () => getBatchDetails(batchId),

    // Stale time: 30 minutes (matches backend cache for COMPLETED batches)
    staleTime: 30 * 60 * 1000,

    // Cache time: 1 hour
    gcTime: 60 * 60 * 1000,

    // Don't refetch on window focus (batch details are relatively static)
    refetchOnWindowFocus: false,

    // Enable query only if batchId is provided
    enabled: Boolean(batchId),
  });
}

/**
 * T106: Query hook for batch errors with offset-based pagination (Phase 7).
 *
 * Fetches paginated list of errors for a specific batch.
 * Errors are sorted by occurredAt DESC (newest first).
 * Cached for 5 minutes (errors are relatively static once logged).
 *
 * @param batchId - Batch unique identifier (UUID)
 * @param page - Page number (0-indexed, default 0)
 * @param size - Page size (default 20)
 * @returns TanStack Query result with paginated errors
 *
 * @example
 * ```tsx
 * const { data, isLoading, error } = useBatchErrors(batchId, 0, 20);
 *
 * if (isLoading) return <Spinner />;
 * if (error) return <ErrorAlert message={error.message} />;
 *
 * return (
 *   <div>
 *     <h2>Errors ({data.totalElements})</h2>
 *     {data.content.map(err => (
 *       <ErrorCard key={err.id} error={err} />
 *     ))}
 *     <Pagination
 *       currentPage={data.page}
 *       totalPages={data.totalPages}
 *     />
 *   </div>
 * );
 * ```
 */
export function useBatchErrors(
  batchId: string,
  page: number = 0,
  size: number = 20
) {
  return useQuery({
    queryKey: batchKeys.errors(batchId, page, size),

    // Fetch function
    queryFn: () => getBatchErrors(batchId, page, size),

    // Stale time: 5 minutes (errors don't change frequently)
    staleTime: 5 * 60 * 1000,

    // Cache time: 10 minutes
    gcTime: 10 * 60 * 1000,

    // Don't refetch on window focus (error list is static)
    refetchOnWindowFocus: false,

    // Enable query only if batchId is provided
    enabled: Boolean(batchId),
  });
}
