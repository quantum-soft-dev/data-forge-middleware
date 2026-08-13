/**
 * Tests for MyPluginsWidget Component
 *
 * Tests widget container with data fetching, activation/deactivation flows,
 * and dialog integration.
 *
 * Feature: My Plugins Dashboard Widget
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { MyPluginsWidget } from '../MyPluginsWidget'
import * as myPluginsQueries from '@/features/my-plugins/api/myPluginsQueries'
import * as myPluginsMutations from '@/features/my-plugins/api/myPluginsMutations'
import type { AccountPluginListResponse, AvailablePlugin } from '@/features/my-plugins'

// Mock the queries and mutations
vi.mock('@/features/my-plugins/api/myPluginsQueries', () => ({
  useAccountPluginsQuery: vi.fn(),
  useAvailablePluginsQuery: vi.fn(),
}))

vi.mock('@/features/my-plugins/api/myPluginsMutations', () => ({
  useActivatePluginMutation: vi.fn(),
  useDeactivatePluginMutation: vi.fn(),
  useRotatePluginSecretMutation: vi.fn(),
}))

vi.mock('@/features/my-plugins/api/pluginLogsQueries', () => ({
  usePluginLogsQuery: vi.fn(() => ({
    data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    isLoading: false,
    isError: false,
    error: null,
  })),
}))

// Mock sonner toast
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

describe('MyPluginsWidget', () => {
  let queryClient: QueryClient

  const mockAccountPluginsResponse: AccountPluginListResponse = {
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

  const mockAvailablePlugins: AvailablePlugin[] = [
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
      isEnabled: true,
    },
  ]

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
  }

  const mockMutationResult = {
    mutate: vi.fn(),
    isPending: false,
    isSuccess: false,
    isError: false,
    error: null,
    variables: undefined,
    reset: vi.fn(),
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    vi.clearAllMocks()

    // Default mock implementations
    vi.mocked(myPluginsMutations.useActivatePluginMutation).mockReturnValue(mockMutationResult as any)
    vi.mocked(myPluginsMutations.useDeactivatePluginMutation).mockReturnValue(mockMutationResult as any)
    vi.mocked(myPluginsMutations.useRotatePluginSecretMutation).mockReturnValue(mockMutationResult as any)
  })

  describe('Loading State', () => {
    it('should show loading state when fetching plugins', () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: true,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: true,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Loading plugins...')).toBeInTheDocument()
    })
  })

  describe('Success State', () => {
    it('should render widget title', () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('My Plugins')).toBeInTheDocument()
    })

    it('should render activated plugins', () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Bit BI')).toBeInTheDocument()
    })

    it('should render available unactivated plugins', () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByText('Analytics')).toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should display error message when query fails', () => {
      const error = new Error('Failed to fetch plugins')
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        error,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: undefined,
        isLoading: false,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByText(/failed to load plugins/i)).toBeInTheDocument()
    })
  })

  describe('Activation Flow', () => {
    it('should open activation dialog when Activate is clicked', async () => {
      const user = userEvent.setup()

      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      // Click Activate on first plugin (bit-bi)
      const activateButtons = screen.getAllByRole('button', { name: 'Activate' })
      await user.click(activateButtons[0])

      // Dialog should open
      expect(screen.getByText('Activate Bit BI')).toBeInTheDocument()
    })

    it('should call activate mutation when form is submitted', async () => {
      const user = userEvent.setup()
      const mockMutate = vi.fn()

      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsMutations.useActivatePluginMutation).mockReturnValue({
        ...mockMutationResult,
        mutate: mockMutate,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      // Open dialog
      const activateButtons = screen.getAllByRole('button', { name: 'Activate' })
      await user.click(activateButtons[0])

      // Fill form and submit
      await user.type(screen.getByLabelText(/tenant id/i), 'my-tenant')
      await user.click(screen.getByRole('button', { name: 'Activate' }))

      expect(mockMutate).toHaveBeenCalledWith(
        {
          pluginId: 'bit-bi',
          request: { pluginData: { tenantId: 'my-tenant' } },
        },
        expect.any(Object)
      )
    })

    it('should reveal the issued secret when the activation returns one', async () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      // The widget subscribes to the secret through the activation mutation.
      const options = vi.mocked(myPluginsMutations.useActivatePluginMutation).mock.calls.at(-1)?.[0]
      expect(options?.onSecretRevealed).toBeTypeOf('function')

      act(() => {
        options!.onSecretRevealed!({
          kind: 'api-key',
          pluginId: 'bit-bi',
          value: 'plk_revealed_secret_value',
        })
      })

      expect(await screen.findByText('plk_revealed_secret_value')).toBeInTheDocument()
      expect(screen.getByText(/won't be shown again/i)).toBeInTheDocument()
    })
  })

  describe('Deactivation Flow', () => {
    it('should call deactivate mutation when Deactivate is clicked', async () => {
      const user = userEvent.setup()
      const mockMutate = vi.fn()

      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsMutations.useDeactivatePluginMutation).mockReturnValue({
        ...mockMutationResult,
        mutate: mockMutate,
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      await user.click(screen.getByRole('button', { name: 'Deactivate' }))

      expect(mockMutate).toHaveBeenCalledWith('bit-bi')
    })
  })

  describe('Pending State', () => {
    it('should show pending state for plugin being activated', () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsMutations.useActivatePluginMutation).mockReturnValue({
        ...mockMutationResult,
        isPending: true,
        variables: { pluginId: 'bit-bi', request: { pluginData: {} } },
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      // bit-bi button should show Activating...
      expect(screen.getByRole('button', { name: 'Activating...' })).toBeDisabled()
    })

    it('should show pending state for plugin being deactivated', () => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsMutations.useDeactivatePluginMutation).mockReturnValue({
        ...mockMutationResult,
        isPending: true,
        variables: 'bit-bi',
      } as any)

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByRole('button', { name: 'Deactivating...' })).toBeDisabled()
    })
  })

  describe('Tabs', () => {
    beforeEach(() => {
      vi.mocked(myPluginsQueries.useAccountPluginsQuery).mockReturnValue({
        data: mockAccountPluginsResponse,
        isLoading: false,
        error: null,
      } as any)
      vi.mocked(myPluginsQueries.useAvailablePluginsQuery).mockReturnValue({
        data: mockAvailablePlugins,
        isLoading: false,
        error: null,
      } as any)
    })

    it('should render Plugins and Logs tabs', () => {
      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      expect(screen.getByRole('tab', { name: /plugins/i })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: /logs/i })).toBeInTheDocument()
    })

    it('should show Plugins tab by default', () => {
      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      const pluginsTab = screen.getByRole('tab', { name: /plugins/i })
      expect(pluginsTab).toHaveAttribute('data-state', 'active')
    })

    it('should switch to Logs tab when clicked', async () => {
      const user = userEvent.setup()

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      const logsTab = screen.getByRole('tab', { name: /logs/i })
      await user.click(logsTab)

      expect(logsTab).toHaveAttribute('data-state', 'active')
    })

    it('should show log entries when Logs tab is active', async () => {
      const user = userEvent.setup()

      render(<MyPluginsWidget />, { wrapper: createWrapper() })

      await user.click(screen.getByRole('tab', { name: /logs/i }))

      // Empty state should be shown since mock returns empty content
      expect(screen.getByText(/no log entries/i)).toBeInTheDocument()
    })
  })
})
