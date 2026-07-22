/**
 * Tests for AuditLogWidget Component
 *
 * Tests widget container with filter state management, pagination,
 * and integration with AuditLogFilters and AuditLogTable.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { AuditLogWidget } from './AuditLogWidget'
import * as pluginQueries from '@/features/plugin-admin/api/pluginQueries'
import type {
  PluginConfig,
  PluginAuditLogPageResponse,
} from '@/entities/plugin/model/types'

// Mock the plugin queries
vi.mock('@/features/plugin-admin/api/pluginQueries', () => ({
  usePluginsQuery: vi.fn(),
  useAuditLogsQuery: vi.fn(),
}))

describe('AuditLogWidget', () => {
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
  ]

  const mockAuditLogs: PluginAuditLogPageResponse = {
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
    ],
    page: 0,
    size: 50,
    totalElements: 1,
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

    // Default mocks
    vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
      data: mockPlugins,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof pluginQueries.usePluginsQuery>)

    vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
      data: mockAuditLogs,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof pluginQueries.useAuditLogsQuery>)
  })

  describe('Rendering', () => {
    it('should render filters and table', () => {
      render(<AuditLogWidget />, { wrapper: createWrapper() })

      expect(screen.getByRole('button', { name: /filters/i })).toBeInTheDocument()
      expect(screen.getByRole('table')).toBeInTheDocument()
    })

    it('should display audit logs in table', () => {
      render(<AuditLogWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('bit-bi')).toBeInTheDocument()
      expect(screen.getByText('Activate')).toBeInTheDocument()
      expect(screen.getByText('Success')).toBeInTheDocument()
    })
  })

  describe('Loading State', () => {
    it('should show loading state when fetching audit logs', () => {
      vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
        data: undefined,
        isLoading: true,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.useAuditLogsQuery>)

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      expect(screen.getByText(/loading audit logs/i)).toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should display error message when query fails', () => {
      const error = new Error('Failed to fetch audit logs')
      vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: true,
        error,
      } as ReturnType<typeof pluginQueries.useAuditLogsQuery>)

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Failed to fetch audit logs')).toBeInTheDocument()
    })

    it('should display generic error message when error is not Error instance', () => {
      vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: true,
        error: 'Unknown error',
      } as unknown as ReturnType<typeof pluginQueries.useAuditLogsQuery>)

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Failed to load audit logs')).toBeInTheDocument()
    })

    it('should have error styling', () => {
      vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: true,
        error: new Error('Test error'),
      } as ReturnType<typeof pluginQueries.useAuditLogsQuery>)

      const { container } = render(<AuditLogWidget />, { wrapper: createWrapper() })

      const errorContainer = container.querySelector('.border-danger-border')
      expect(errorContainer).toBeInTheDocument()
      expect(errorContainer).toHaveClass('bg-danger-bg')
    })
  })

  describe('Initial Plugin ID', () => {
    it('should pre-fill pluginId filter from initialPluginId prop', () => {
      render(<AuditLogWidget initialPluginId="bit-bi" />, {
        wrapper: createWrapper(),
      })

      // Check that useAuditLogsQuery was called with the initial pluginId
      expect(pluginQueries.useAuditLogsQuery).toHaveBeenCalledWith(
        expect.objectContaining({
          pluginId: 'bit-bi',
        })
      )
    })

    it('should show Active badge when initialPluginId is set', async () => {
      render(<AuditLogWidget initialPluginId="bit-bi" />, {
        wrapper: createWrapper(),
      })

      expect(screen.getByText('Active')).toBeInTheDocument()
    })
  })

  describe('Filter State Management', () => {
    it('should update filters when filter changes', async () => {
      const user = userEvent.setup()

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      // Open filter panel
      await user.click(screen.getByRole('button', { name: /filters/i }))

      // Change plugin filter
      await user.selectOptions(screen.getByLabelText(/plugin/i), 'bit-bi')

      // Verify query was called with new filter
      await waitFor(() => {
        expect(pluginQueries.useAuditLogsQuery).toHaveBeenCalledWith(
          expect.objectContaining({
            pluginId: 'bit-bi',
            page: 0, // Reset to first page
          })
        )
      })
    })
  })

  describe('Pagination', () => {
    it('should handle page change', async () => {
      const user = userEvent.setup()

      // Mock more pages
      vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
        data: {
          ...mockAuditLogs,
          totalPages: 5,
          totalElements: 250,
        },
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.useAuditLogsQuery>)

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      // Click next page
      await user.click(screen.getByRole('button', { name: /next/i }))

      // Verify query was called with new page (0-indexed internally)
      await waitFor(() => {
        expect(pluginQueries.useAuditLogsQuery).toHaveBeenCalledWith(
          expect.objectContaining({
            page: 1, // 0-indexed page
          })
        )
      })
    })

    it('should handle page size change', async () => {
      const user = userEvent.setup()

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      // Change page size using the label
      const pageSizeSelect = screen.getByLabelText(/per page/i)
      await user.selectOptions(pageSizeSelect, '20')

      // Verify query was called with new size and reset page
      await waitFor(() => {
        expect(pluginQueries.useAuditLogsQuery).toHaveBeenCalledWith(
          expect.objectContaining({
            size: 20,
            page: 0, // Reset to first page
          })
        )
      })
    })
  })

  describe('Plugin Options', () => {
    it('should pass plugin IDs to filter component', async () => {
      const user = userEvent.setup()

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      // Open filter panel
      await user.click(screen.getByRole('button', { name: /filters/i }))

      // Check that plugin options are rendered
      const pluginSelect = screen.getByLabelText(/plugin/i)
      expect(pluginSelect).toContainHTML('bit-bi')
    })

    it('should handle empty plugin list', async () => {
      const user = userEvent.setup()

      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      // Open filter panel
      await user.click(screen.getByRole('button', { name: /filters/i }))

      // Plugin select should still be visible with empty options
      expect(screen.getByLabelText(/plugin/i)).toBeInTheDocument()
    })
  })

  describe('Empty Audit Logs', () => {
    it('should show empty state when no audit logs', () => {
      vi.mocked(pluginQueries.useAuditLogsQuery).mockReturnValue({
        data: {
          content: [],
          page: 0,
          size: 50,
          totalElements: 0,
          totalPages: 0,
        },
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.useAuditLogsQuery>)

      render(<AuditLogWidget />, { wrapper: createWrapper() })

      expect(screen.getByText(/no audit logs found/i)).toBeInTheDocument()
    })
  })
})
