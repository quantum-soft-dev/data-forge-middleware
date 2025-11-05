/**
 * T036: Unit tests for useCreateComparison mutation hook
 *
 * Tests comparison creation functionality with validation,
 * error handling, and success callbacks.
 *
 * Feature: 009-markdown-user-story (User Story 1 - Select Files for Comparison)
 * Priority: P1 (MVP)
 *
 * CRITICAL: This test MUST FAIL before implementation (TDD Red phase).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { useCreateComparison } from './useCreateComparison';
import * as comparisonApi from '@/entities/comparison/api/comparisonApi';

// Mock the comparisonApi module
vi.mock('@/entities/comparison/api/comparisonApi');

describe('useCreateComparison', () => {
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

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
  };

  const renderUseCreateComparison = () => {
    return renderHook(() => useCreateComparison(), { wrapper: createWrapper() });
  };

  describe('Successful Comparison Creation', () => {
    it('should create comparison with selected file IDs', async () => {
      // Given: Mock API returns successful comparison response
      const mockResponse = {
        id: 45,
        currentBatchId: 123,
        targetBatchId: 120,
        accountId: 10,
        status: 'PENDING',
        createdAt: '2025-11-03T10:30:00Z',
        startedAt: null,
        completedAt: null,
        totalFilesCompared: 0,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
      };

      vi.spyOn(comparisonApi, 'createComparison').mockResolvedValue(mockResponse);

      // When: Hook is rendered and mutation is triggered
      const { result } = renderUseCreateComparison();

      const request = {
        currentBatchId: 123,
        targetBatchId: 120,
        fileIds: [501, 502, 503],
      };

      result.current.mutate(request);

      // Then: Mutation should succeed and return comparison
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data).toEqual(mockResponse);
      expect(comparisonApi.createComparison).toHaveBeenCalledWith(request);
      expect(comparisonApi.createComparison).toHaveBeenCalledTimes(1);
    });

    it('should create comparison with null fileIds (compare all files)', async () => {
      // Given: Mock API returns successful comparison response
      const mockResponse = {
        id: 46,
        currentBatchId: 124,
        targetBatchId: 121,
        accountId: 10,
        status: 'PENDING',
        createdAt: '2025-11-03T11:00:00Z',
        startedAt: null,
        completedAt: null,
        totalFilesCompared: 0,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
      };

      vi.spyOn(comparisonApi, 'createComparison').mockResolvedValue(mockResponse);

      // When: Hook is called with null fileIds
      const { result } = renderUseCreateComparison();

      const request = {
        currentBatchId: 124,
        targetBatchId: 121,
        fileIds: null,  // Compare all files
      };

      result.current.mutate(request);

      // Then: Mutation should succeed
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.data).toEqual(mockResponse);
      expect(comparisonApi.createComparison).toHaveBeenCalledWith(request);
    });

    it('should call onSuccess callback when comparison is created', async () => {
      // Given: Mock API and success callback
      const mockResponse = {
        id: 47,
        currentBatchId: 125,
        targetBatchId: 122,
        accountId: 10,
        status: 'PENDING',
        createdAt: '2025-11-03T12:00:00Z',
        startedAt: null,
        completedAt: null,
        totalFilesCompared: 0,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 0,
      };

      vi.spyOn(comparisonApi, 'createComparison').mockResolvedValue(mockResponse);

      const onSuccessMock = vi.fn();

      // When: Hook is rendered with onSuccess callback
      const { result } = renderHook(
        () => useCreateComparison({ onSuccess: onSuccessMock }),
        { wrapper: createWrapper() }
      );

      result.current.mutate({
        currentBatchId: 125,
        targetBatchId: 122,
        fileIds: [601, 602],
      });

      // Then: onSuccess callback should be called
      await waitFor(() => {
        expect(onSuccessMock).toHaveBeenCalledWith(mockResponse);
      });

      expect(onSuccessMock).toHaveBeenCalledTimes(1);
    });
  });

  describe('Error Handling', () => {
    it('should handle API error (400 Bad Request - no files selected)', async () => {
      // Given: Mock API returns 400 error
      const mockError = {
        response: {
          status: 400,
          data: {
            timestamp: '2025-11-03T10:30:00Z',
            status: 400,
            error: 'Bad Request',
            message: 'At least one file must be selected for comparison',
            path: '/api/v1/comparisons',
          },
        },
      };

      vi.spyOn(comparisonApi, 'createComparison').mockRejectedValue(mockError);

      // When: Hook is called with empty fileIds
      const { result } = renderUseCreateComparison();

      result.current.mutate({
        currentBatchId: 123,
        targetBatchId: 120,
        fileIds: [],  // Empty array
      });

      // Then: Mutation should fail
      await waitFor(() => {
        expect(result.current.isError).toBe(true);
      });

      expect(result.current.error).toEqual(mockError);
    });

    it('should handle API error (403 Forbidden - access denied)', async () => {
      // Given: Mock API returns 403 error
      const mockError = {
        response: {
          status: 403,
          data: {
            timestamp: '2025-11-03T10:30:00Z',
            status: 403,
            error: 'Forbidden',
            message: 'Access denied: You do not have permission to access batch 120',
            path: '/api/v1/comparisons',
          },
        },
      };

      vi.spyOn(comparisonApi, 'createComparison').mockRejectedValue(mockError);

      // When: Hook is called with batch from different account
      const { result } = renderUseCreateComparison();

      result.current.mutate({
        currentBatchId: 123,
        targetBatchId: 120,
        fileIds: [501],
      });

      // Then: Mutation should fail
      await waitFor(() => {
        expect(result.current.isError).toBe(true);
      });

      expect(result.current.error).toEqual(mockError);
    });

    it('should handle network error', async () => {
      // Given: Mock API throws network error
      const mockError = new Error('Network error');

      vi.spyOn(comparisonApi, 'createComparison').mockRejectedValue(mockError);

      // When: Hook is called
      const { result } = renderUseCreateComparison();

      result.current.mutate({
        currentBatchId: 123,
        targetBatchId: 120,
        fileIds: [501],
      });

      // Then: Mutation should fail
      await waitFor(() => {
        expect(result.current.isError).toBe(true);
      });

      expect(result.current.error).toEqual(mockError);
    });
  });

  describe('Loading State', () => {
    it('should have isPending=true during API call', async () => {
      // Given: Mock API with delayed response
      vi.spyOn(comparisonApi, 'createComparison').mockImplementation(
        () =>
          new Promise((resolve) =>
            setTimeout(() => resolve({
              id: 48,
              currentBatchId: 126,
              targetBatchId: 123,
              accountId: 10,
              status: 'PENDING',
              createdAt: '2025-11-03T13:00:00Z',
              startedAt: null,
              completedAt: null,
              totalFilesCompared: 0,
              filesChanged: 0,
              filesAdded: 0,
              filesUnchanged: 0,
              totalChangeSize: 0,
            }), 100)
          )
      );

      // When: Hook is called
      const { result } = renderUseCreateComparison();

      expect(result.current.isPending).toBe(false);

      result.current.mutate({
        currentBatchId: 126,
        targetBatchId: 123,
        fileIds: [701],
      });

      // Then: isPending should be true during the call
      await waitFor(() => {
        expect(result.current.isPending).toBe(true);
      });

      // Wait for completion
      await waitFor(() => {
        expect(result.current.isSuccess).toBe(true);
      });

      expect(result.current.isPending).toBe(false);
    });
  });
});
