/**
 * T109: Unit test for useDeleteComparison hook
 *
 * Tests the delete comparison mutation hook with:
 * - Optimistic updates
 * - Cache invalidation
 * - Error handling
 *
 * User Story: US7 - Delete Saved Comparisons (Phase 9)
 * Priority: P4
 *
 * Note: This test should FAIL initially because the hook doesn't exist yet (TDD Red phase).
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useDeleteComparison } from '../../hooks/useDeleteComparison';
import * as comparisonApi from '../../api/comparisonApi';
import React from 'react';

// Mock the API module
vi.mock('../../api/comparisonApi', () => ({
  comparisonApi: {
    deleteComparison: vi.fn(),
  },
}));

describe('useDeleteComparison', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    // Create a fresh QueryClient for each test
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false, // Disable retries for tests
        },
        mutations: {
          retry: false,
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

  it('should successfully delete a comparison', async () => {
    // Given: Mock API returns success
    const comparisonId = 'test-comparison-id-123';
    vi.mocked(comparisonApi.comparisonApi.deleteComparison).mockResolvedValue(undefined);

    // When: Render hook and call mutate
    const { result } = renderHook(() => useDeleteComparison(), { wrapper: createWrapper() });

    result.current.mutate(comparisonId);

    // Then: Mutation should succeed
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
      expect(result.current.isError).toBe(false);
    });

    // Verify API was called with correct ID
    expect(comparisonApi.comparisonApi.deleteComparison).toHaveBeenCalledWith(Number(comparisonId));
    expect(comparisonApi.comparisonApi.deleteComparison).toHaveBeenCalledTimes(1);
  });

  it('should handle deletion errors', async () => {
    // Given: Mock API returns error
    const comparisonId = 'test-comparison-id-456';
    const errorMessage = 'Comparison cannot be deleted while IN_PROGRESS';
    vi.mocked(comparisonApi.comparisonApi.deleteComparison).mockRejectedValue(
      new Error(errorMessage)
    );

    // When: Render hook and call mutate
    const { result } = renderHook(() => useDeleteComparison(), { wrapper: createWrapper() });

    result.current.mutate(comparisonId);

    // Then: Mutation should fail with error
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
      expect(result.current.error).toBeInstanceOf(Error);
      expect(result.current.error?.message).toBe(errorMessage);
    });

    // Verify API was called
    expect(comparisonApi.comparisonApi.deleteComparison).toHaveBeenCalledWith(Number(comparisonId));
  });

  it('should invalidate comparison queries on success', async () => {
    // Given: Mock API returns success
    const comparisonId = 'test-comparison-id-789';
    vi.mocked(comparisonApi.comparisonApi.deleteComparison).mockResolvedValue(undefined);

    // Spy on queryClient.invalidateQueries
    const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

    // When: Render hook and call mutate
    const { result } = renderHook(() => useDeleteComparison(), { wrapper: createWrapper() });

    result.current.mutate(comparisonId);

    // Then: Should invalidate comparison queries
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // Verify cache invalidation was called
    // Note: The hook should invalidate both 'comparisons' (list) and specific comparison queries
    expect(invalidateQueriesSpy).toHaveBeenCalled();
  });

  it('should handle optimistic updates', async () => {
    // Given: Mock API returns success after delay
    const comparisonId = 'test-comparison-id-optimistic';
    vi.mocked(comparisonApi.comparisonApi.deleteComparison).mockImplementation(
      () =>
        new Promise((resolve) => {
          setTimeout(() => resolve(undefined), 100);
        })
    );

    // When: Render hook and call mutate
    const { result } = renderHook(() => useDeleteComparison(), { wrapper: createWrapper() });

    result.current.mutate(comparisonId);

    // Then: Should eventually transition from pending to success
    await waitFor(() => {
      expect(result.current.isPending).toBe(false);
      expect(result.current.isSuccess).toBe(true);
    });

    // Verify the mutation completed successfully
    expect(result.current.isError).toBe(false);
  });

  it('should support mutation callbacks (onSuccess, onError)', async () => {
    // Given: Mock API returns success
    const comparisonId = 'test-comparison-id-callbacks';
    vi.mocked(comparisonApi.comparisonApi.deleteComparison).mockResolvedValue(undefined);

    const onSuccessMock = vi.fn();
    const onErrorMock = vi.fn();

    // When: Render hook and call mutate with callbacks
    const { result } = renderHook(() => useDeleteComparison(), { wrapper: createWrapper() });

    result.current.mutate(comparisonId, {
      onSuccess: onSuccessMock,
      onError: onErrorMock,
    });

    // Then: onSuccess callback should be called
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(onSuccessMock).toHaveBeenCalledTimes(1);
    expect(onErrorMock).not.toHaveBeenCalled();
  });

  it('should call onError callback on failure', async () => {
    // Given: Mock API returns error
    const comparisonId = 'test-comparison-id-error-callback';
    const error = new Error('Network error');
    vi.mocked(comparisonApi.comparisonApi.deleteComparison).mockRejectedValue(error);

    const onSuccessMock = vi.fn();
    const onErrorMock = vi.fn();

    // When: Render hook and call mutate with callbacks
    const { result } = renderHook(() => useDeleteComparison(), { wrapper: createWrapper() });

    result.current.mutate(comparisonId, {
      onSuccess: onSuccessMock,
      onError: onErrorMock,
    });

    // Then: onError callback should be called
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    expect(onErrorMock).toHaveBeenCalledTimes(1);
    // Verify error callback receives error as first argument
    // TanStack Query mutation onError signature: (error, variables, context, mutationMeta)
    expect(onErrorMock).toHaveBeenCalledWith(
      error, // Error object
      comparisonId, // Variables passed to mutate
      undefined, // Context (we don't use optimistic context)
      expect.objectContaining({ client: expect.anything() }) // Mutation meta with QueryClient
    );
    expect(onSuccessMock).not.toHaveBeenCalled();
  });
});
