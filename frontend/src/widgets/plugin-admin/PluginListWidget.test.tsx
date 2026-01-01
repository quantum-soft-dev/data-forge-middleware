/**
 * Tests for PluginListWidget Component
 *
 * Tests widget container with data fetching, error states,
 * and integration with PluginListView.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { PluginListWidget } from './PluginListWidget'
import * as pluginQueries from '@/features/plugin-admin/api/pluginQueries'
import type { PluginConfig } from '@/entities/plugin/model/types'

// Mock the plugin queries
vi.mock('@/features/plugin-admin/api/pluginQueries', () => ({
  usePluginsQuery: vi.fn(),
}))

describe('PluginListWidget', () => {
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

  describe('Loading State', () => {
    it('should pass isLoading to PluginListView', () => {
      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: true,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget />, { wrapper: createWrapper() })

      expect(screen.getByText(/loading plugins/i)).toBeInTheDocument()
    })
  })

  describe('Success State', () => {
    it('should render plugins when data is loaded', () => {
      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: mockPlugins,
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Bit BI Integration')).toBeInTheDocument()
      expect(screen.getByText('Analytics Plugin')).toBeInTheDocument()
    })

    it('should pass empty array when data is undefined', () => {
      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget />, { wrapper: createWrapper() })

      expect(screen.getByText(/no plugins registered/i)).toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should display error message when query fails', () => {
      const error = new Error('Failed to fetch plugins')
      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: true,
        error,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Failed to fetch plugins')).toBeInTheDocument()
    })

    it('should display generic error message when error is not Error instance', () => {
      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: true,
        error: 'Unknown error',
      } as unknown as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Failed to load plugins')).toBeInTheDocument()
    })

    it('should have error styling', () => {
      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: true,
        error: new Error('Test error'),
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      const { container } = render(<PluginListWidget />, { wrapper: createWrapper() })

      const errorContainer = container.querySelector('.border-red-200')
      expect(errorContainer).toBeInTheDocument()
      expect(errorContainer).toHaveClass('bg-red-50')
    })
  })

  describe('onPluginClick Callback', () => {
    it('should pass onPluginClick to PluginListView', async () => {
      const user = userEvent.setup()
      const onPluginClick = vi.fn()

      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: mockPlugins,
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget onPluginClick={onPluginClick} />, {
        wrapper: createWrapper(),
      })

      // Click the Audit Logs button for the first plugin
      const auditButtons = screen.getAllByRole('button', { name: /audit logs/i })
      await user.click(auditButtons[0])

      expect(onPluginClick).toHaveBeenCalledWith('bit-bi')
    })
  })

  describe('onHistoryClick Callback', () => {
    it('should pass onHistoryClick to PluginListView', async () => {
      const user = userEvent.setup()
      const onHistoryClick = vi.fn()

      vi.mocked(pluginQueries.usePluginsQuery).mockReturnValue({
        data: mockPlugins,
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof pluginQueries.usePluginsQuery>)

      render(<PluginListWidget onHistoryClick={onHistoryClick} />, {
        wrapper: createWrapper(),
      })

      // Click the SQL History button for the first plugin
      const historyButtons = screen.getAllByRole('button', { name: /sql history/i })
      await user.click(historyButtons[0])

      expect(onHistoryClick).toHaveBeenCalledWith('bit-bi')
    })
  })
})
