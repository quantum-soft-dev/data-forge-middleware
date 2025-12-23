/**
 * Tests for my-plugins query hooks
 *
 * Tests account plugins queries and available plugins queries.
 *
 * Feature: My Plugins Dashboard Widget
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactNode } from 'react'
import { useAccountPluginsQuery, useAvailablePluginsQuery } from '../myPluginsQueries'
import * as api from '../myPluginsApi'
import type { AccountPluginListResponse, AvailablePlugin } from '../../model/types'

// Mock the API
vi.mock('../myPluginsApi', () => ({
  fetchAccountPlugins: vi.fn(),
  fetchAvailablePlugins: vi.fn(),
}))

describe('myPluginsQueries', () => {
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

  describe('useAccountPluginsQuery', () => {
    const mockResponse: AccountPluginListResponse = {
      content: [
        {
          pluginId: 'bit-bi',
          pluginName: 'Bit BI',
          isActive: true,
          activatedAt: '2025-12-01T10:00:00Z',
          deactivatedAt: null,
          lastUsedAt: '2025-12-20T15:30:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    }

    it('should fetch account plugins successfully', async () => {
      vi.mocked(api.fetchAccountPlugins).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => useAccountPluginsQuery(false), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toEqual(mockResponse)
      expect(api.fetchAccountPlugins).toHaveBeenCalledWith({ includeInactive: false })
    })

    it('should fetch account plugins with includeInactive=true', async () => {
      vi.mocked(api.fetchAccountPlugins).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => useAccountPluginsQuery(true), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(api.fetchAccountPlugins).toHaveBeenCalledWith({ includeInactive: true })
    })

    it('should handle API error', async () => {
      const mockError = new Error('Failed to fetch plugins')
      vi.mocked(api.fetchAccountPlugins).mockRejectedValue(mockError)

      const { result } = renderHook(() => useAccountPluginsQuery(false), { wrapper })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(result.current.error).toBe(mockError)
    })

    it('should have correct query key for cache invalidation', async () => {
      vi.mocked(api.fetchAccountPlugins).mockResolvedValue(mockResponse)

      const { result } = renderHook(() => useAccountPluginsQuery(false), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      // Verify query is cached with correct key
      const cachedData = queryClient.getQueryData(['plugins', 'account', { includeInactive: false }])
      expect(cachedData).toEqual(mockResponse)
    })
  })

  describe('useAvailablePluginsQuery', () => {
    const mockPlugins: AvailablePlugin[] = [
      {
        pluginId: 'bit-bi',
        displayName: 'Bit BI',
        version: '1.0.0',
        isEnabled: true,
      },
      {
        pluginId: 'analytics',
        displayName: 'Analytics',
        version: '2.0.0',
        isEnabled: false,
      },
    ]

    it('should fetch available plugins successfully', async () => {
      vi.mocked(api.fetchAvailablePlugins).mockResolvedValue(mockPlugins)

      const { result } = renderHook(() => useAvailablePluginsQuery(), { wrapper })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toEqual(mockPlugins)
      expect(api.fetchAvailablePlugins).toHaveBeenCalled()
    })

    it('should handle API error', async () => {
      const mockError = new Error('Failed to fetch available plugins')
      vi.mocked(api.fetchAvailablePlugins).mockRejectedValue(mockError)

      const { result } = renderHook(() => useAvailablePluginsQuery(), { wrapper })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(result.current.error).toBe(mockError)
    })
  })
})
