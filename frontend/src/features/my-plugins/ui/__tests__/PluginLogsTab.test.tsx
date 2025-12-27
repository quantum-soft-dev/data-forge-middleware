/**
 * Tests for PluginLogsTab component
 *
 * Tests the plugin logs tab UI for displaying log entries.
 *
 * Feature: Plugin Logs Tab
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PluginLogsTab } from '../PluginLogsTab'
import * as pluginLogsQueries from '../../api/pluginLogsQueries'
import type { PluginLogPageResponse } from '../../model/types'

// Mock the query hook
vi.mock('../../api/pluginLogsQueries', () => ({
  usePluginLogsQuery: vi.fn(),
}))

describe('PluginLogsTab', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    })
    vi.clearAllMocks()
  })

  const renderWithProvider = (ui: React.ReactElement) => {
    return render(
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    )
  }

  const mockLogsResponse: PluginLogPageResponse = {
    content: [
      {
        id: 1,
        actionType: 'SQL_GENERATION_COMPLETED',
        success: true,
        errorMessage: undefined,
        metadata: {
          batchId: '550e8400-e29b-41d4-a716-446655440000',
          insertCount: 100,
          updateCount: 50,
          deleteCount: 10,
          s3Key: 'plugins/bit-bi/account/site/2025-12-27.sql',
          durationMs: 1500,
        },
        occurredAt: '2025-12-27T10:30:00Z',
      },
      {
        id: 2,
        actionType: 'SQL_GENERATION_STARTED',
        success: true,
        metadata: {
          batchId: '550e8400-e29b-41d4-a716-446655440000',
        },
        occurredAt: '2025-12-27T10:29:58Z',
      },
      {
        id: 3,
        actionType: 'ACTIVATE',
        success: true,
        occurredAt: '2025-12-01T10:00:00Z',
      },
    ],
    page: 0,
    size: 20,
    totalElements: 3,
    totalPages: 1,
  }

  it('should display loading state', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('should display error state', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('Failed to fetch logs'),
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    expect(screen.getByText(/failed to fetch logs/i)).toBeInTheDocument()
  })

  it('should display empty state when no logs', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: {
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      },
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    expect(screen.getByText(/no log entries/i)).toBeInTheDocument()
  })

  it('should display log entries', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: mockLogsResponse,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // Check that log entries are displayed with user-friendly labels
    expect(screen.getByText(/sql generated/i)).toBeInTheDocument()
    expect(screen.getByText(/generating sql\.\.\./i)).toBeInTheDocument()
    expect(screen.getByText(/plugin activated/i)).toBeInTheDocument()
  })

  it('should display success badge for successful operations', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: mockLogsResponse,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // All log entries in mock are successful
    const successBadges = screen.getAllByText(/success/i)
    expect(successBadges.length).toBeGreaterThan(0)
  })

  it('should display error badge and message for failed operations', () => {
    const logsWithError: PluginLogPageResponse = {
      content: [
        {
          id: 4,
          actionType: 'SQL_GENERATION_FAILED',
          success: false,
          errorMessage: 'Table name contains invalid characters',
          metadata: {
            batchId: '550e8400-e29b-41d4-a716-446655440000',
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

    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: logsWithError,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // Both the action type and badge contain "Failed"
    const failedElements = screen.getAllByText(/failed/i)
    expect(failedElements.length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText(/table name contains invalid characters/i)).toBeInTheDocument()
  })

  it('should display SQL generation statistics in metadata', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: mockLogsResponse,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // Check for statistics display with context (INSERT/UPDATE/DELETE labels)
    expect(screen.getByText(/\+100 INSERT/)).toBeInTheDocument()
    expect(screen.getByText(/~50 UPDATE/)).toBeInTheDocument()
    expect(screen.getByText(/-10 DELETE/)).toBeInTheDocument()
  })

  it('should format timestamp correctly', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: mockLogsResponse,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // Verify date is formatted - look for timestamp elements
    const timestamps = screen.getAllByText(/dec.*2025|2025.*dec/i)
    expect(timestamps.length).toBeGreaterThan(0)
  })

  it('should call usePluginLogsQuery with correct pluginId', () => {
    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: mockLogsResponse,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    expect(pluginLogsQueries.usePluginLogsQuery).toHaveBeenCalledWith('bit-bi', 0)
  })

  it('should filter out EVENT_DISPATCHED from visible logs', () => {
    const logsWithEventDispatched: PluginLogPageResponse = {
      content: [
        {
          id: 1,
          actionType: 'EVENT_DISPATCHED',
          success: true,
          occurredAt: '2025-12-27T10:30:00Z',
        },
        {
          id: 2,
          actionType: 'SQL_GENERATION_COMPLETED',
          success: true,
          metadata: {
            batchId: '550e8400-e29b-41d4-a716-446655440000',
            insertCount: 100,
          },
          occurredAt: '2025-12-27T10:30:01Z',
        },
        {
          id: 3,
          actionType: 'EVENT_FAILED',
          success: false,
          errorMessage: 'Technical error',
          occurredAt: '2025-12-27T10:30:02Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    }

    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: logsWithEventDispatched,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // SQL_GENERATION_COMPLETED should be shown with user-friendly label
    expect(screen.getByText(/sql generated/i)).toBeInTheDocument()

    // EVENT_DISPATCHED and EVENT_FAILED should NOT be shown
    expect(screen.queryByText(/event dispatched/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/event failed/i)).not.toBeInTheDocument()
  })

  it('should display user-friendly labels for action types', () => {
    const logsWithActivate: PluginLogPageResponse = {
      content: [
        {
          id: 1,
          actionType: 'ACTIVATE',
          success: true,
          occurredAt: '2025-12-01T10:00:00Z',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    }

    vi.mocked(pluginLogsQueries.usePluginLogsQuery).mockReturnValue({
      data: logsWithActivate,
      isLoading: false,
      isError: false,
      error: null,
    } as any)

    renderWithProvider(<PluginLogsTab pluginId="bit-bi" />)

    // Should show "Plugin Activated" instead of just "Activate"
    expect(screen.getByText(/plugin activated/i)).toBeInTheDocument()
  })
})
