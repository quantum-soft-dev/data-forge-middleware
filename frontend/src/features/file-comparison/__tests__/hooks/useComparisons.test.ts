/**
 * T121: Unit test for useComparisons hook
 *
 * Tests the list comparisons query hook with:
 * - Pagination support
 * - Status filtering
 * - Error handling
 * - Loading states
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * Priority: P3
 *
 * Note: This test should FAIL initially because the hook doesn't exist yet (TDD Red phase).
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useComparisons } from '../../hooks/useComparisons';
import * as comparisonApi from '../../api/comparisonApi';
import React from 'react';

// Mock the API module
vi.mock('../../api/comparisonApi', () => ({
  comparisonApi: {
    listComparisons: vi.fn(),
  },
}));

describe('useComparisons', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    // Create a fresh QueryClient for each test
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false, // Disable retries for tests
        },
      },
    });

    // Clear all mocks
    vi.clearAllMocks();
  });

  /**
   * Test wrapper component that provides QueryClient context
   */
  const createWrapper = () => {
    return ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: queryClient }, children);
  };

  it('should fetch comparisons list successfully', async () => {
    // Given: Mock API returns paginated response
    const mockResponse = {
      content: [
        {
          id: 1,
          currentBatchId: 'batch-1',
          targetBatchId: 'batch-2',
          status: 'COMPLETED',
          totalFilesCompared: 10,
          filesChanged: 5,
          filesAdded: 2,
          filesUnchanged: 3,
          createdAt: '2025-11-04T12:00:00Z',
        },
        {
          id: 2,
          currentBatchId: 'batch-3',
          targetBatchId: 'batch-4',
          status: 'FAILED',
          totalFilesCompared: 0,
          filesChanged: 0,
          filesAdded: 0,
          filesUnchanged: 0,
          errorMessage: 'Test error',
          createdAt: '2025-11-04T11:00:00Z',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 2,
      totalPages: 1,
    };

    vi.mocked(comparisonApi.comparisonApi.listComparisons).mockResolvedValue(mockResponse);

    // When: Render hook
    const { result } = renderHook(() => useComparisons(), { wrapper: createWrapper() });

    // Then: Should fetch data successfully
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.data).toEqual(mockResponse);
    expect(result.current.error).toBeNull();
    expect(comparisonApi.comparisonApi.listComparisons).toHaveBeenCalledWith({
      page: 0,
      size: 10,
    });
  });

  it('should handle pagination parameters', async () => {
    // Given: Mock API returns paginated response
    const mockResponse = {
      content: [],
      page: 2,
      size: 20,
      totalElements: 45,
      totalPages: 3,
    };

    vi.mocked(comparisonApi.comparisonApi.listComparisons).mockResolvedValue(mockResponse);

    // When: Render hook with custom pagination
    const { result } = renderHook(
      () => useComparisons({ page: 2, size: 20 }),
      { wrapper: createWrapper() }
    );

    // Then: Should call API with correct parameters
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(comparisonApi.comparisonApi.listComparisons).toHaveBeenCalledWith({
      page: 2,
      size: 20,
    });
  });

  it('should filter by status', async () => {
    // Given: Mock API returns filtered response
    const mockResponse = {
      content: [
        {
          id: 1,
          currentBatchId: 'batch-1',
          targetBatchId: 'batch-2',
          status: 'COMPLETED',
          totalFilesCompared: 10,
          filesChanged: 5,
          filesAdded: 2,
          filesUnchanged: 3,
          createdAt: '2025-11-04T12:00:00Z',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    };

    vi.mocked(comparisonApi.comparisonApi.listComparisons).mockResolvedValue(mockResponse);

    // When: Render hook with status filter
    const { result } = renderHook(
      () => useComparisons({ status: 'COMPLETED' }),
      { wrapper: createWrapper() }
    );

    // Then: Should call API with status filter
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(comparisonApi.comparisonApi.listComparisons).toHaveBeenCalledWith({
      page: 0,
      size: 10,
      status: 'COMPLETED',
    });
  });

  it('should handle fetch errors', async () => {
    // Given: Mock API returns error
    const errorMessage = 'Network error';
    vi.mocked(comparisonApi.comparisonApi.listComparisons).mockRejectedValue(
      new Error(errorMessage)
    );

    // When: Render hook
    const { result } = renderHook(() => useComparisons(), { wrapper: createWrapper() });

    // Then: Should handle error
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.error?.message).toBe(errorMessage);
    expect(result.current.data).toBeUndefined();
  });

  it('should show loading state initially', () => {
    // Given: Mock API with pending promise
    vi.mocked(comparisonApi.comparisonApi.listComparisons).mockImplementation(
      () => new Promise(() => {}) // Never resolves
    );

    // When: Render hook
    const { result } = renderHook(() => useComparisons(), { wrapper: createWrapper() });

    // Then: Should be in loading state
    expect(result.current.isLoading).toBe(true);
    expect(result.current.isSuccess).toBe(false);
    expect(result.current.isError).toBe(false);
    expect(result.current.data).toBeUndefined();
  });

  it('should use correct query key for caching', async () => {
    // Given: Mock API returns response
    const mockResponse = {
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    };

    vi.mocked(comparisonApi.comparisonApi.listComparisons).mockResolvedValue(mockResponse);

    // When: Render hook
    const { result } = renderHook(
      () => useComparisons({ page: 1, status: 'COMPLETED' }),
      { wrapper: createWrapper() }
    );

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // Then: Verify query is cached with correct key
    // The query key should include parameters for proper cache invalidation
    const cachedQueries = queryClient.getQueryCache().getAll();
    expect(cachedQueries.length).toBeGreaterThan(0);

    // Verify the query key includes the pagination and filter parameters
    const queryKey = cachedQueries[0].queryKey as any[];
    expect(queryKey[0]).toBe('comparisons');
    expect(queryKey[1]).toMatchObject({ page: 1, status: 'COMPLETED' });
  });
});
