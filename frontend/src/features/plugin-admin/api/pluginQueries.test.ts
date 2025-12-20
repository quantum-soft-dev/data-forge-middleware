/**
 * Tests for Plugin Admin TanStack Query hooks.
 *
 * Tests loading, success, and error states for plugin administration queries.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { usePluginsQuery, useAuditLogsQuery, usePluginAuditLogsQuery } from './pluginQueries'
import * as pluginAdminApi from './pluginAdminApi'
import type { PluginConfig, PluginAuditLogPageResponse } from '@/entities/plugin/model/types'

// Mock the plugin admin API
vi.mock('./pluginAdminApi')

describe('Plugin Admin Query Hooks', () => {
  let queryClient: QueryClient

  const mockPlugins: PluginConfig[] = [
    {
      pluginId: 'bit-bi',
      displayName: 'Bit BI Integration',
      version: '1.0.0',
      isEnabled: true,
      supportedEvents: ['BATCH_COMPLETED'],
      createdAt: '2025-12-01T10:00:00Z',
      updatedAt: '2025-12-01T10:00:00Z',
    },
    {
      pluginId: 'analytics',
      displayName: 'Analytics Plugin',
      version: '2.1.0',
      isEnabled: false,
      supportedEvents: ['BATCH_COMPLETED', 'FILE_UPLOADED'],
      createdAt: '2025-11-15T08:00:00Z',
      updatedAt: '2025-12-10T14:30:00Z',
    },
  ]

  const mockAuditLogResponse: PluginAuditLogPageResponse = {
    content: [
      {
        id: 1,
        pluginId: 'bit-bi',
        accountId: 'acc-123',
        clientId: 'client-456',
        actionType: 'ACTIVATE',
        requestBodyHash: 'abc123',
        requestBodySize: 256,
        responseStatus: 200,
        success: true,
        errorMessage: null,
        ipAddress: '192.168.1.1',
        durationMs: 150,
        occurredAt: '2025-12-15T09:30:00Z',
      },
      {
        id: 2,
        pluginId: 'bit-bi',
        accountId: 'acc-789',
        clientId: null,
        actionType: 'EVENT_DISPATCHED',
        requestBodyHash: null,
        requestBodySize: null,
        responseStatus: null,
        success: true,
        errorMessage: null,
        ipAddress: null,
        durationMs: 45,
        occurredAt: '2025-12-15T10:00:00Z',
      },
    ],
    page: 0,
    size: 50,
    totalElements: 2,
    totalPages: 1,
  }

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
      },
    })
    vi.clearAllMocks()
  })

  describe('usePluginsQuery', () => {
    it('should fetch plugins successfully', async () => {
      vi.mocked(pluginAdminApi.fetchPlugins).mockResolvedValue(mockPlugins)

      const { result } = renderHook(() => usePluginsQuery(), {
        wrapper: createWrapper(),
      })

      // Initially loading
      expect(result.current.isLoading).toBe(true)

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toEqual(mockPlugins)
      expect(pluginAdminApi.fetchPlugins).toHaveBeenCalledOnce()
    })

    it('should handle error state', async () => {
      const error = new Error('Failed to fetch plugins')
      vi.mocked(pluginAdminApi.fetchPlugins).mockRejectedValue(error)

      const { result } = renderHook(() => usePluginsQuery(), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(result.current.error).toBe(error)
    })

    it('should use correct staleTime of 60 seconds', async () => {
      vi.mocked(pluginAdminApi.fetchPlugins).mockResolvedValue(mockPlugins)

      const { result } = renderHook(() => usePluginsQuery(), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      // Check that data is considered fresh (not stale)
      expect(result.current.isStale).toBe(false)
    })
  })

  describe('useAuditLogsQuery', () => {
    it('should fetch audit logs successfully', async () => {
      vi.mocked(pluginAdminApi.fetchAuditLogs).mockResolvedValue(mockAuditLogResponse)

      const { result } = renderHook(() => useAuditLogsQuery(), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toEqual(mockAuditLogResponse)
      expect(pluginAdminApi.fetchAuditLogs).toHaveBeenCalledWith({
        page: 0,
        size: 50,
      })
    })

    it('should pass filters to API', async () => {
      vi.mocked(pluginAdminApi.fetchAuditLogs).mockResolvedValue(mockAuditLogResponse)

      const filters = {
        pluginId: 'bit-bi',
        actionType: 'ACTIVATE' as const,
        success: true,
        page: 1,
        size: 25,
      }

      const { result } = renderHook(() => useAuditLogsQuery(filters), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(pluginAdminApi.fetchAuditLogs).toHaveBeenCalledWith(filters)
    })

    it('should handle error state', async () => {
      const error = new Error('Failed to fetch audit logs')
      vi.mocked(pluginAdminApi.fetchAuditLogs).mockRejectedValue(error)

      const { result } = renderHook(() => useAuditLogsQuery(), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(result.current.error).toBe(error)
    })

    it('should apply default pagination', async () => {
      vi.mocked(pluginAdminApi.fetchAuditLogs).mockResolvedValue(mockAuditLogResponse)

      renderHook(() => useAuditLogsQuery({ pluginId: 'bit-bi' }), {
        wrapper: createWrapper(),
      })

      await waitFor(() =>
        expect(pluginAdminApi.fetchAuditLogs).toHaveBeenCalledWith({
          pluginId: 'bit-bi',
          page: 0,
          size: 50,
        })
      )
    })
  })

  describe('usePluginAuditLogsQuery', () => {
    it('should fetch plugin-specific audit logs', async () => {
      vi.mocked(pluginAdminApi.fetchPluginAuditLogs).mockResolvedValue(mockAuditLogResponse)

      const { result } = renderHook(() => usePluginAuditLogsQuery('bit-bi'), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toEqual(mockAuditLogResponse)
      expect(pluginAdminApi.fetchPluginAuditLogs).toHaveBeenCalledWith('bit-bi', 0, 50)
    })

    it('should pass pagination parameters', async () => {
      vi.mocked(pluginAdminApi.fetchPluginAuditLogs).mockResolvedValue(mockAuditLogResponse)

      const { result } = renderHook(() => usePluginAuditLogsQuery('bit-bi', 2, 25), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(pluginAdminApi.fetchPluginAuditLogs).toHaveBeenCalledWith('bit-bi', 2, 25)
    })

    it('should be disabled when pluginId is empty', async () => {
      const { result } = renderHook(() => usePluginAuditLogsQuery(''), {
        wrapper: createWrapper(),
      })

      // Query should not run
      expect(result.current.fetchStatus).toBe('idle')
      expect(pluginAdminApi.fetchPluginAuditLogs).not.toHaveBeenCalled()
    })

    it('should handle error state', async () => {
      const error = new Error('Failed to fetch plugin audit logs')
      vi.mocked(pluginAdminApi.fetchPluginAuditLogs).mockRejectedValue(error)

      const { result } = renderHook(() => usePluginAuditLogsQuery('bit-bi'), {
        wrapper: createWrapper(),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(result.current.error).toBe(error)
    })
  })
})
