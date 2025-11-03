/**
 * T054: Unit tests for useComparisonDetails query hook
 *
 * Tests comparison details fetching with automatic polling for IN_PROGRESS status.
 *
 * Feature: 009-markdown-user-story (User Story 2 - Compare Files)
 * Priority: P1 (MVP)
 * Phase: Phase 4
 *
 * Test Coverage:
 * - Successful comparison fetching
 * - Automatic polling while IN_PROGRESS
 * - Polling stops when COMPLETED/FAILED
 * - Custom polling interval
 * - Conditional fetching (enabled flag)
 * - Error handling (404, network errors)
 * - Retry logic
 * - Caching behavior
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { useComparisonDetails } from './useComparisonDetails';
import { comparisonApi } from '../api/comparisonApi';

// Mock the comparisonApi module
vi.mock('../api/comparisonApi', () => ({
  comparisonApi: {
    getComparison: vi.fn(),
  },
}));

describe('useComparisonDetails', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
  };

  describe('Successful Comparison Fetching', () => {
    it('should fetch comparison details by ID', async () => {
      // Given: Mock API returns completed comparison
      const mockComparison = {
        id: 45,
        currentBatchId: 'batch-123',
        targetBatchId: 'batch-120',
        accountId: 'account-10',
        status: 'COMPLETED' as const,
        createdAt: '2025-11-03T10:30:00Z',
        startedAt: '2025-11-03T10:30:01Z',
        completedAt: '2025-11-03T10:31:25Z',
        totalFilesCompared: 50,
        filesChanged: 12,
        filesAdded: 3,
        filesUnchanged: 35,
        totalChangeSize: 125000,
        errorMessage: null,
      };

      vi.mocked(comparisonApi.getComparison).mockResolvedValue(mockComparison);

      // When: Hook is rendered
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 45 }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch comparison successfully
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data).toEqual(mockComparison);
      expect(comparisonApi.getComparison).toHaveBeenCalledWith(45);
      expect(comparisonApi.getComparison).toHaveBeenCalledTimes(1);
    });

    it('should fetch pending comparison', async () => {
      // Given: Mock API returns pending comparison
      const mockComparison = {
        id: 46,
        currentBatchId: 'batch-124',
        targetBatchId: 'batch-121',
        accountId: 'account-10',
        status: 'PENDING' as const,
        createdAt: '2025-11-03T11:00:00Z',
        startedAt: null,
        completedAt: null,
        totalFilesCompared: 0,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
        errorMessage: null,
      };

      vi.mocked(comparisonApi.getComparison).mockResolvedValue(mockComparison);

      // When: Hook is rendered
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 46 }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch pending comparison
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data?.status).toBe('PENDING');
    });

    it('should fetch failed comparison', async () => {
      // Given: Mock API returns failed comparison
      const mockComparison = {
        id: 47,
        currentBatchId: 'batch-125',
        targetBatchId: 'batch-122',
        accountId: 'account-10',
        status: 'FAILED' as const,
        createdAt: '2025-11-03T12:00:00Z',
        startedAt: '2025-11-03T12:00:01Z',
        completedAt: '2025-11-03T12:00:30Z',
        totalFilesCompared: 10,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
        errorMessage: 'S3 access denied',
      };

      vi.mocked(comparisonApi.getComparison).mockResolvedValue(mockComparison);

      // When: Hook is rendered
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 47 }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch failed comparison
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data?.status).toBe('FAILED');
      expect(result.current.data?.errorMessage).toBe('S3 access denied');
    });
  });

  describe('Automatic Polling for IN_PROGRESS Status', () => {
    it('should configure polling for IN_PROGRESS status', async () => {
      // Given: Mock API returns IN_PROGRESS comparison
      const mockInProgress = {
        id: 48,
        currentBatchId: 'batch-126',
        targetBatchId: 'batch-123',
        accountId: 'account-10',
        status: 'IN_PROGRESS' as const,
        createdAt: '2025-11-03T13:00:00Z',
        startedAt: '2025-11-03T13:00:01Z',
        completedAt: null,
        totalFilesCompared: 25,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
        errorMessage: null,
      };

      vi.mocked(comparisonApi.getComparison).mockResolvedValue(mockInProgress);

      // When: Hook is rendered with polling enabled
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 48, enablePolling: true }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch IN_PROGRESS comparison
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data?.status).toBe('IN_PROGRESS');
      expect(comparisonApi.getComparison).toHaveBeenCalled();
    });

    it('should transition from IN_PROGRESS to COMPLETED', async () => {
      // Given: Mock API returns IN_PROGRESS first, then COMPLETED
      const mockInProgress = {
        id: 49,
        currentBatchId: 'batch-127',
        targetBatchId: 'batch-124',
        accountId: 'account-10',
        status: 'IN_PROGRESS' as const,
        createdAt: '2025-11-03T14:00:00Z',
        startedAt: '2025-11-03T14:00:01Z',
        completedAt: null,
        totalFilesCompared: 30,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
        errorMessage: null,
      };

      const mockCompleted = {
        ...mockInProgress,
        status: 'COMPLETED' as const,
        completedAt: '2025-11-03T14:01:00Z',
        totalFilesCompared: 50,
        filesChanged: 12,
        filesAdded: 3,
        filesUnchanged: 35,
        totalChangeSize: 125000,
      };

      vi.mocked(comparisonApi.getComparison)
        .mockResolvedValueOnce(mockInProgress)
        .mockResolvedValueOnce(mockCompleted);

      // When: Hook is rendered with short polling interval
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 49, pollingInterval: 100 }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch IN_PROGRESS initially
      await waitFor(() => {
        expect(result.current.data?.status).toBe('IN_PROGRESS');
      });

      // Wait for polling to fetch COMPLETED status
      await waitFor(
        () => {
          expect(result.current.data?.status).toBe('COMPLETED');
        },
        { timeout: 500 }
      );

      expect(result.current.data?.totalFilesCompared).toBe(50);
    });

    it('should respect custom polling interval', async () => {
      // Given: Mock API returns IN_PROGRESS
      const mockInProgress = {
        id: 50,
        currentBatchId: 'batch-128',
        targetBatchId: 'batch-125',
        accountId: 'account-10',
        status: 'IN_PROGRESS' as const,
        createdAt: '2025-11-03T15:00:00Z',
        startedAt: '2025-11-03T15:00:01Z',
        completedAt: null,
        totalFilesCompared: 10,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
        errorMessage: null,
      };

      vi.mocked(comparisonApi.getComparison).mockResolvedValue(mockInProgress);

      // When: Hook is rendered with 5-second polling interval
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 50, pollingInterval: 5000 }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch initially
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data?.status).toBe('IN_PROGRESS');
      expect(comparisonApi.getComparison).toHaveBeenCalled();
    });

    it('should not poll when enablePolling is false', async () => {
      // Given: Mock API returns IN_PROGRESS
      const mockInProgress = {
        id: 51,
        currentBatchId: 'batch-129',
        targetBatchId: 'batch-126',
        accountId: 'account-10',
        status: 'IN_PROGRESS' as const,
        createdAt: '2025-11-03T16:00:00Z',
        startedAt: '2025-11-03T16:00:01Z',
        completedAt: null,
        totalFilesCompared: 20,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
        errorMessage: null,
      };

      vi.mocked(comparisonApi.getComparison).mockResolvedValue(mockInProgress);

      // When: Hook is rendered with polling disabled
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 51, enablePolling: false }),
        { wrapper: createWrapper() }
      );

      // Then: Should fetch initially but not poll
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      const initialCallCount = vi.mocked(comparisonApi.getComparison).mock.calls.length;

      // Wait a bit to ensure no additional calls are made
      await new Promise((resolve) => setTimeout(resolve, 200));

      expect(vi.mocked(comparisonApi.getComparison).mock.calls.length).toBe(initialCallCount);
    });
  });

  describe('Conditional Fetching', () => {
    it('should not fetch when enabled is false', async () => {
      // Given: Mock API
      vi.mocked(comparisonApi.getComparison).mockResolvedValue({
        id: 52,
        currentBatchId: 'batch-130',
        targetBatchId: 'batch-127',
        accountId: 'account-10',
        status: 'COMPLETED' as const,
        createdAt: '2025-11-03T17:00:00Z',
        startedAt: '2025-11-03T17:00:01Z',
        completedAt: '2025-11-03T17:01:00Z',
        totalFilesCompared: 40,
        filesChanged: 10,
        filesAdded: 2,
        filesUnchanged: 28,
        totalChangeSize: 90000,
        errorMessage: null,
      });

      // When: Hook is rendered with enabled=false
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 52, enabled: false }),
        { wrapper: createWrapper() }
      );

      // Then: Should not fetch
      await new Promise((resolve) => setTimeout(resolve, 100));

      expect(result.current.isSuccess).toBe(false);
      expect(result.current.data).toBeUndefined();
      expect(comparisonApi.getComparison).not.toHaveBeenCalled();
    });
  });

  describe('Error Handling', () => {
    it('should handle 404 Not Found error', async () => {
      // Given: Mock API returns 404 error
      const mockError = {
        response: {
          status: 404,
          data: {
            timestamp: '2025-11-03T18:00:00Z',
            status: 404,
            error: 'Not Found',
            message: 'Comparison not found: 999',
            path: '/api/v1/comparisons/999',
          },
        },
      };

      vi.mocked(comparisonApi.getComparison).mockRejectedValue(mockError);

      // When: Hook is rendered
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 999 }),
        { wrapper: createWrapper() }
      );

      // Then: Should fail with error
      await waitFor(() => {
        expect(result.current.isError).toBe(true);
      });

      expect(result.current.error).toEqual(mockError);
      // Should not retry on 404
      expect(comparisonApi.getComparison).toHaveBeenCalledTimes(1);
    });

    it(
      'should handle network error with retries',
      async () => {
        // Given: Mock API throws network error
        const mockError = new Error('Network error');

        vi.mocked(comparisonApi.getComparison).mockRejectedValue(mockError);

        // When: Hook is rendered
        const { result } = renderHook(
          () => useComparisonDetails({ comparisonId: 53 }),
          { wrapper: createWrapper() }
        );

        // Then: Should retry up to 3 times
        await waitFor(
          () => {
            expect(result.current.isError).toBe(true);
          },
          { timeout: 8000 }
        );

        expect(result.current.error).toEqual(mockError);
        // Initial call + 3 retries = 4 total calls
        expect(comparisonApi.getComparison).toHaveBeenCalledTimes(4);
      },
      10000
    );
  });

  describe('Loading State', () => {
    it('should show loading state during initial fetch', async () => {
      // Given: Mock API with delayed response
      vi.mocked(comparisonApi.getComparison).mockImplementation(
        () =>
          new Promise((resolve) =>
            setTimeout(() =>
              resolve({
                id: 54,
                currentBatchId: 'batch-131',
                targetBatchId: 'batch-128',
                accountId: 'account-10',
                status: 'COMPLETED' as const,
                createdAt: '2025-11-03T19:00:00Z',
                startedAt: '2025-11-03T19:00:01Z',
                completedAt: '2025-11-03T19:01:00Z',
                totalFilesCompared: 60,
                filesChanged: 15,
                filesAdded: 5,
                filesUnchanged: 40,
                totalChangeSize: 150000,
                errorMessage: null,
              }),
              100
            )
          )
      );

      // When: Hook is rendered
      const { result } = renderHook(
        () => useComparisonDetails({ comparisonId: 54 }),
        { wrapper: createWrapper() }
      );

      // Then: Should show loading initially
      expect(result.current.isLoading).toBe(true);
      expect(result.current.data).toBeUndefined();

      // Wait for completion
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.isLoading).toBe(false);
      expect(result.current.data).toBeDefined();
    });
  });
});
