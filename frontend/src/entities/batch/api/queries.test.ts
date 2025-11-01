/**
 * T033: Unit tests for useBatchHistory hook
 *
 * Tests React Query infinite query hook for batch history pagination.
 * Covers cursor management, pagination, caching, and error states.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { useBatchHistory, batchKeys } from './queries';
import * as batchApi from './batchApi';
import type { CursorPageResponse, BatchSummary } from '../model/types';

// Mock the batchApi module
vi.mock('./batchApi');

describe('batchKeys', () => {
  it('should generate correct query keys', () => {
    expect(batchKeys.all).toEqual(['batches']);
    expect(batchKeys.history()).toEqual(['batches', 'history']);
    expect(batchKeys.historyWithLimit(20)).toEqual(['batches', 'history', { limit: 20 }]);
    expect(batchKeys.historyWithLimit(50)).toEqual(['batches', 'history', { limit: 50 }]);
  });
});

describe('useBatchHistory', () => {
  let queryClient: QueryClient;

  // Helper to create wrapper with QueryClient
  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
  };

  // Mock data
  const mockBatch1: BatchSummary = {
    id: '123e4567-e89b-12d3-a456-426614174000',
    siteId: '123e4567-e89b-12d3-a456-426614174001',
    status: 'COMPLETED',
    s3Path: 's3://bucket/path',
    uploadedFilesCount: 5,
    totalSize: 1024000,
    hasErrors: false,
    startedAt: '2025-11-01T10:00:00Z',
    completedAt: '2025-11-01T10:05:00Z',
  };

  const mockBatch2: BatchSummary = {
    id: '123e4567-e89b-12d3-a456-426614174002',
    siteId: '123e4567-e89b-12d3-a456-426614174001',
    status: 'COMPLETED',
    s3Path: 's3://bucket/path2',
    uploadedFilesCount: 3,
    totalSize: 512000,
    hasErrors: true,
    startedAt: '2025-11-01T09:00:00Z',
    completedAt: '2025-11-01T09:05:00Z',
  };

  const mockFirstPage: CursorPageResponse<BatchSummary> = {
    items: [mockBatch1],
    nextCursor: '2025-11-01T09:00:00_123e4567-e89b-12d3-a456-426614174002',
    hasNext: true,
  };

  const mockSecondPage: CursorPageResponse<BatchSummary> = {
    items: [mockBatch2],
    nextCursor: null,
    hasNext: false,
  };

  beforeEach(() => {
    // Create fresh QueryClient for each test
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false, // Disable retries for tests
        },
      },
    });
    vi.clearAllMocks();
  });

  it('should fetch first page with default limit', async () => {
    // Mock API call
    vi.spyOn(batchApi, 'listBatches').mockResolvedValueOnce(mockFirstPage);

    const { result } = renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    // Initial state
    expect(result.current.isLoading).toBe(true);
    expect(result.current.data).toBeUndefined();

    // Wait for data to load
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // Verify API was called with correct params
    expect(batchApi.listBatches).toHaveBeenCalledWith(undefined, 20);

    // Verify data structure
    expect(result.current.data?.pages).toHaveLength(1);
    expect(result.current.data?.pages[0]).toEqual(mockFirstPage);
    expect(result.current.hasNextPage).toBe(true);
  });

  it('should fetch with custom limit', async () => {
    vi.spyOn(batchApi, 'listBatches').mockResolvedValueOnce(mockFirstPage);

    const { result } = renderHook(() => useBatchHistory(50), {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(batchApi.listBatches).toHaveBeenCalledWith(undefined, 50);
  });

  it('should fetch next page with cursor', async () => {
    vi.spyOn(batchApi, 'listBatches')
      .mockResolvedValueOnce(mockFirstPage)
      .mockResolvedValueOnce(mockSecondPage);

    const { result } = renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    // Wait for first page
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(result.current.data?.pages).toHaveLength(1);
    expect(result.current.hasNextPage).toBe(true);

    // Fetch next page
    result.current.fetchNextPage();

    await waitFor(() => {
      expect(result.current.data?.pages).toHaveLength(2);
    });

    // Verify second API call used cursor from first page
    expect(batchApi.listBatches).toHaveBeenCalledWith(
      '2025-11-01T09:00:00_123e4567-e89b-12d3-a456-426614174002',
      20
    );

    // Verify all data is present
    expect(result.current.data?.pages[0]).toEqual(mockFirstPage);
    expect(result.current.data?.pages[1]).toEqual(mockSecondPage);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('should not have next page when hasNext is false', async () => {
    const singlePage: CursorPageResponse<BatchSummary> = {
      items: [mockBatch1],
      nextCursor: null,
      hasNext: false,
    };

    vi.spyOn(batchApi, 'listBatches').mockResolvedValueOnce(singlePage);

    const { result } = renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(result.current.hasNextPage).toBe(false);
  });

  it('should handle empty results', async () => {
    const emptyPage: CursorPageResponse<BatchSummary> = {
      items: [],
      nextCursor: null,
      hasNext: false,
    };

    vi.spyOn(batchApi, 'listBatches').mockResolvedValueOnce(emptyPage);

    const { result } = renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(result.current.data?.pages[0].items).toHaveLength(0);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('should handle errors', async () => {
    const error = new Error('Failed to fetch batches');
    vi.spyOn(batchApi, 'listBatches').mockRejectedValueOnce(error);

    const { result } = renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    expect(result.current.error).toEqual(error);
  });

  it('should use correct query key', () => {
    vi.spyOn(batchApi, 'listBatches').mockResolvedValueOnce(mockFirstPage);

    const { result } = renderHook(() => useBatchHistory(20), {
      wrapper: createWrapper(),
    });

    // Query key should be accessible through queryClient
    const queries = queryClient.getQueryCache().getAll();
    const batchQuery = queries.find((q) =>
      JSON.stringify(q.queryKey).includes('batches')
    );

    expect(batchQuery?.queryKey).toEqual(['batches', 'history', { limit: 20 }]);
  });

  it('should have correct cache configuration', async () => {
    vi.spyOn(batchApi, 'listBatches').mockResolvedValueOnce(mockFirstPage);

    renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      const queries = queryClient.getQueryCache().getAll();
      expect(queries.length).toBeGreaterThan(0);
    });

    // Check staleTime and gcTime
    const queries = queryClient.getQueryCache().getAll();
    const batchQuery = queries.find((q) =>
      JSON.stringify(q.queryKey).includes('batches')
    );

    expect(batchQuery?.options.staleTime).toBe(5 * 60 * 1000); // 5 minutes
    expect(batchQuery?.options.gcTime).toBe(10 * 60 * 1000); // 10 minutes
  });

  it('should support infinite scroll pattern', async () => {
    // Simulate multiple pages
    const page1: CursorPageResponse<BatchSummary> = {
      items: [mockBatch1],
      nextCursor: 'cursor1',
      hasNext: true,
    };

    const page2: CursorPageResponse<BatchSummary> = {
      items: [mockBatch2],
      nextCursor: 'cursor2',
      hasNext: true,
    };

    const page3: CursorPageResponse<BatchSummary> = {
      items: [{ ...mockBatch1, id: 'new-id' }],
      nextCursor: null,
      hasNext: false,
    };

    vi.spyOn(batchApi, 'listBatches')
      .mockResolvedValueOnce(page1)
      .mockResolvedValueOnce(page2)
      .mockResolvedValueOnce(page3);

    const { result } = renderHook(() => useBatchHistory(), {
      wrapper: createWrapper(),
    });

    // Load page 1
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(result.current.data?.pages).toHaveLength(1);

    // Load page 2
    result.current.fetchNextPage();
    await waitFor(() => {
      expect(result.current.data?.pages).toHaveLength(2);
    });

    // Load page 3
    result.current.fetchNextPage();
    await waitFor(() => {
      expect(result.current.data?.pages).toHaveLength(3);
    });

    expect(result.current.hasNextPage).toBe(false);

    // Flatten all items (common pattern in infinite scroll)
    const allItems = result.current.data?.pages.flatMap((page) => page.items) ?? [];
    expect(allItems).toHaveLength(3);
  });
});
