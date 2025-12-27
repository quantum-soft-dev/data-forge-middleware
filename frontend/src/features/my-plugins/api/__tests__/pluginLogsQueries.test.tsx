/**
 * Tests for plugin logs query hooks
 *
 * Tests plugin logs queries for user-facing log viewing.
 *
 * Feature: Plugin Logs Tab
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactNode } from 'react'
import { usePluginLogsQuery } from '../pluginLogsQueries'
import * as api from '../pluginLogsApi'
import type { PluginLogPageResponse } from '../../model/types'

// Mock the API
vi.mock('../pluginLogsApi', () => ({
  fetchPluginLogs: vi.fn(),
}))

describe('pluginLogsQueries', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    vi.clearAllMocks()
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  describe('usePluginLogsQuery', () => {
    const mockResponse: PluginLogPageResponse = {
      content: [
        {
          id: 1,
          actionType: 'SQL_GENERATION_COMPLETED',
          success: true,
          errorMessage: undefined,
          metadata: {
            batchId: '550e8400-e29b-41d4-a716-446655440000',
            siteId: '660e8400-e29b-41d4-a716-446655440001',
            insertCount: 100,
            updateCount: 50,
            deleteCount: 10,
            s3Key: 'plugins/bit-bi/account123/site456/2025-12-27_10-30-00.sql',
            durationMs: 1500,
          },
          occurredAt: '2025-12-27T10:30:00Z',
        },
        {
          id: 2,
          actionType: 'SQL_GENERATION_STARTED',
          success: true,
          errorMessage: undefined,
          metadata: {
            batchId: '550e8400-e29b-41d4-a716-446655440000',
            siteId: '660e8400-e29b-41d4-a716-446655440001',
          },
          occurredAt: '2025-12-27T10:29:58Z',
        },
        {
          id: 3,
          actionType: 'ACTIVATE',
          success: true,
          errorMessage: undefined,
          metadata: undefined,
          occurredAt: '2025-12-01T10:00:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    }

    it('should fetch plugin logs successfully', async () => {
      vi.mocked(api.fetchPluginLogs).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => usePluginLogsQuery('bit-bi', 0), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toEqual(mockResponse)
      expect(api.fetchPluginLogs).toHaveBeenCalledWith({ pluginId: 'bit-bi', page: 0 })
    })

    it('should fetch plugin logs with pagination', async () => {
      const page2Response: PluginLogPageResponse = {
        ...mockResponse,
        page: 1,
        content: [],
      }
      vi.mocked(api.fetchPluginLogs).mockResolvedValue(page2Response)

      const { result } = renderHook(() => usePluginLogsQuery('bit-bi', 1), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data?.page).toBe(1)
      expect(api.fetchPluginLogs).toHaveBeenCalledWith({ pluginId: 'bit-bi', page: 1 })
    })

    it('should handle API error', async () => {
      const mockError = new Error('Failed to fetch plugin logs')
      vi.mocked(api.fetchPluginLogs).mockRejectedValue(mockError)

      const { result } = renderHook(() => usePluginLogsQuery('bit-bi', 0), { wrapper })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(result.current.error).toBe(mockError)
    })

    it('should have correct query key for cache invalidation', async () => {
      vi.mocked(api.fetchPluginLogs).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => usePluginLogsQuery('bit-bi', 0), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      // Verify query is cached with correct key
      const cachedData = queryClient.getQueryData(['plugins', 'logs', 'bit-bi', { page: 0 }])
      expect(cachedData).toEqual(mockResponse)
    })

    it('should refetch on pluginId change', async () => {
      vi.mocked(api.fetchPluginLogs).mockResolvedValue(mockResponse)

      const { result, rerender } = renderHook(
        ({ pluginId }) => usePluginLogsQuery(pluginId, 0),
        { wrapper, initialProps: { pluginId: 'bit-bi' } }
      )

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      // Change pluginId
      rerender({ pluginId: 'analytics' })

      await waitFor(() => expect(api.fetchPluginLogs).toHaveBeenCalledTimes(2))

      expect(api.fetchPluginLogs).toHaveBeenCalledWith({ pluginId: 'analytics', page: 0 })
    })

    it('should include error logs with failure status', async () => {
      const responseWithError: PluginLogPageResponse = {
        content: [
          {
            id: 4,
            actionType: 'SQL_GENERATION_FAILED',
            success: false,
            errorMessage: 'Table name contains invalid characters',
            metadata: {
              batchId: '550e8400-e29b-41d4-a716-446655440000',
              siteId: '660e8400-e29b-41d4-a716-446655440001',
              durationMs: 500,
            },
            occurredAt: '2025-12-27T10:30:00Z',
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }
      vi.mocked(api.fetchPluginLogs).mockResolvedValue(responseWithError)

      const { result } = renderHook(() => usePluginLogsQuery('bit-bi', 0), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data?.content[0].success).toBe(false)
      expect(result.current.data?.content[0].errorMessage).toBe('Table name contains invalid characters')
    })

    it('should not fetch when pluginId is empty', async () => {
      vi.mocked(api.fetchPluginLogs).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => usePluginLogsQuery('', 0), { wrapper })

      // Query should be disabled when pluginId is empty
      expect(result.current.isFetching).toBe(false)
      expect(api.fetchPluginLogs).not.toHaveBeenCalled()
    })
  })
})
