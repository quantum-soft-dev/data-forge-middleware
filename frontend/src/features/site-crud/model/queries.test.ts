/**
 * Tests for site management TanStack Query hooks.
 *
 * Tests optimistic updates, cache invalidation, and error handling
 * for the useUpdateSiteStatus hook.
 *
 * Feature: 007-adding-a-site (T048, US2)
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { useUpdateSiteStatus, siteKeys } from './queries';
import * as siteApi from '@/entities/site/api/siteApi';
import type { Site } from '@/entities/site/model/types';

// Mock the site API
vi.mock('@/entities/site/api/siteApi');

describe('useUpdateSiteStatus', () => {
  let queryClient: QueryClient;

  const mockSites: Site[] = [
    {
      id: 'site-1',
      accountId: 'account-1',
      siteName: 'example.com',
      name: 'Example Site',
      isActive: true,
      retentionDays: 45,
      createdAt: '2024-01-01T00:00:00Z',
    },
    {
      id: 'site-2',
      accountId: 'account-1',
      siteName: 'test.com',
      name: 'Test Site',
      isActive: false,
      retentionDays: 45,
      createdAt: '2024-01-02T00:00:00Z',
    },
  ];

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
  };

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
        mutations: {
          retry: false,
        },
      },
    });

    // Reset mocks
    vi.clearAllMocks();
  });

  describe('Optimistic Updates', () => {
    it('should optimistically update site status to inactive', async () => {
      // Set initial cache data
      queryClient.setQueryData(siteKeys.list(), mockSites);

      // Mock API call
      vi.mocked(siteApi.deactivateUserSite).mockResolvedValue({
        ...mockSites[0],
        isActive: false,
      });

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      // Trigger mutation
      result.current.mutate({ siteId: 'site-1', isActive: false });

      // Immediately check optimistic update (before API resolves)
      await waitFor(() => {
        const cachedSites = queryClient.getQueryData<Site[]>(siteKeys.list());
        const updatedSite = cachedSites?.find((s) => s.id === 'site-1');
        expect(updatedSite?.isActive).toBe(false);
      });
    });

    it('should optimistically update site status to active', async () => {
      // Set initial cache data
      queryClient.setQueryData(siteKeys.list(), mockSites);

      // Mock API call
      vi.mocked(siteApi.activateUserSite).mockResolvedValue({
        ...mockSites[1],
        isActive: true,
      });

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      // Trigger mutation
      result.current.mutate({ siteId: 'site-2', isActive: true });

      // Immediately check optimistic update
      await waitFor(() => {
        const cachedSites = queryClient.getQueryData<Site[]>(siteKeys.list());
        const updatedSite = cachedSites?.find((s) => s.id === 'site-2');
        expect(updatedSite?.isActive).toBe(true);
      });
    });

    it('should not affect other sites during optimistic update', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      vi.mocked(siteApi.deactivateUserSite).mockResolvedValue({
        ...mockSites[0],
        isActive: false,
      });

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-1', isActive: false });

      await waitFor(() => {
        const cachedSites = queryClient.getQueryData<Site[]>(siteKeys.list());
        const unchangedSite = cachedSites?.find((s) => s.id === 'site-2');
        expect(unchangedSite?.isActive).toBe(false); // Should remain unchanged
      });
    });
  });

  describe('Error Handling and Rollback', () => {
    it('should rollback optimistic update on API error', async () => {
      // Set initial cache data
      queryClient.setQueryData(siteKeys.list(), mockSites);

      // Mock API failure
      vi.mocked(siteApi.deactivateUserSite).mockRejectedValue(
        new Error('Network error')
      );

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      // Trigger mutation
      result.current.mutate({ siteId: 'site-1', isActive: false });

      // Wait for error
      await waitFor(() => expect(result.current.isError).toBe(true));

      // Check rollback - should restore original status
      const cachedSites = queryClient.getQueryData<Site[]>(siteKeys.list());
      const rolledBackSite = cachedSites?.find((s) => s.id === 'site-1');
      expect(rolledBackSite?.isActive).toBe(true); // Rolled back to original
    });

    it('should handle 403 Forbidden error', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      const forbiddenError = {
        response: {
          status: 403,
          data: { message: 'Forbidden' },
        },
      };

      vi.mocked(siteApi.deactivateUserSite).mockRejectedValue(forbiddenError);

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-1', isActive: false });

      await waitFor(() => {
        expect(result.current.isError).toBe(true);
        expect(result.current.error).toBe(forbiddenError);
      });

      // Verify rollback
      const cachedSites = queryClient.getQueryData<Site[]>(siteKeys.list());
      const rolledBackSite = cachedSites?.find((s) => s.id === 'site-1');
      expect(rolledBackSite?.isActive).toBe(true);
    });

    it('should handle 404 Not Found error', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      const notFoundError = {
        response: {
          status: 404,
          data: { message: 'Site not found' },
        },
      };

      vi.mocked(siteApi.activateUserSite).mockRejectedValue(notFoundError);

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-2', isActive: true });

      await waitFor(() => {
        expect(result.current.isError).toBe(true);
      });
    });
  });

  describe('Cache Invalidation', () => {
    it('should invalidate site list cache after successful update', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      vi.mocked(siteApi.deactivateUserSite).mockResolvedValue({
        ...mockSites[0],
        isActive: false,
      });

      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-1', isActive: false });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      // Verify cache invalidation was called
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: siteKeys.list(),
      });
    });

    it('should invalidate cache even after error (onSettled)', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      vi.mocked(siteApi.deactivateUserSite).mockRejectedValue(
        new Error('API Error')
      );

      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-1', isActive: false });

      await waitFor(() => expect(result.current.isError).toBe(true));

      // Should still invalidate cache to refetch fresh data
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: siteKeys.list(),
      });
    });
  });

  describe('API Calls', () => {
    it('should call activateUserSite when isActive is true', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      vi.mocked(siteApi.activateUserSite).mockResolvedValue({
        ...mockSites[1],
        isActive: true,
      });

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-2', isActive: true });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(siteApi.activateUserSite).toHaveBeenCalledWith('site-2');
      expect(siteApi.deactivateUserSite).not.toHaveBeenCalled();
    });

    it('should call deactivateUserSite when isActive is false', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      vi.mocked(siteApi.deactivateUserSite).mockResolvedValue({
        ...mockSites[0],
        isActive: false,
      });

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate({ siteId: 'site-1', isActive: false });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(siteApi.deactivateUserSite).toHaveBeenCalledWith('site-1');
      expect(siteApi.activateUserSite).not.toHaveBeenCalled();
    });
  });

  describe('Loading States', () => {
    it('should track pending state during mutation', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      // Create a promise we control
      let resolveActivate: (value: Site) => void;
      const activatePromise = new Promise<Site>((resolve) => {
        resolveActivate = resolve;
      });

      vi.mocked(siteApi.activateUserSite).mockReturnValue(activatePromise);

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      // Start mutation
      result.current.mutate({ siteId: 'site-2', isActive: true });

      // Should be pending
      await waitFor(() => expect(result.current.isPending).toBe(true));

      // Resolve the promise
      resolveActivate!({ ...mockSites[1], isActive: true });

      // Should complete
      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.isPending).toBe(false);
    });
  });

  describe('Mutation Callbacks', () => {
    it('should call onSuccess callback', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      vi.mocked(siteApi.activateUserSite).mockResolvedValue({
        ...mockSites[1],
        isActive: true,
      });

      const onSuccess = vi.fn();

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate(
        { siteId: 'site-2', isActive: true },
        { onSuccess }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(onSuccess).toHaveBeenCalled();
    });

    it('should call onError callback', async () => {
      queryClient.setQueryData(siteKeys.list(), mockSites);

      const error = new Error('API Error');
      vi.mocked(siteApi.deactivateUserSite).mockRejectedValue(error);

      const onError = vi.fn();

      const { result } = renderHook(() => useUpdateSiteStatus(), { wrapper: createWrapper() });

      result.current.mutate(
        { siteId: 'site-1', isActive: false },
        { onError }
      );

      await waitFor(() => expect(result.current.isError).toBe(true));

      // TanStack Query v5 onError signature: (error, variables, context, mutationContext)
      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError).toHaveBeenCalledWith(
        error,
        { siteId: 'site-1', isActive: false },
        expect.objectContaining({
          previousSites: mockSites,
        }),
        expect.any(Object)
      );
    });
  });
});
