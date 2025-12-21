/**
 * Tests for AuditLogTable Component
 *
 * Tests table rendering, loading states, empty states, pagination,
 * and data display.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuditLogTable } from './AuditLogTable'
import type { PluginAuditLogEntry } from '@/entities/plugin/model/types'

describe('AuditLogTable', () => {
  const mockLogs: PluginAuditLogEntry[] = [
    {
      id: 1,
      pluginId: 'bit-bi',
      accountId: 'acc-12345678-abcd-efgh-ijkl-mnopqrstuvwx',
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
      pluginId: 'analytics',
      accountId: null,
      clientId: null,
      actionType: 'EVENT_FAILED',
      requestBodyHash: null,
      requestBodySize: null,
      responseStatus: 500,
      success: false,
      errorMessage: 'Connection timeout',
      ipAddress: null,
      durationMs: 30000,
      occurredAt: '2025-12-15T10:00:00Z',
    },
  ]

  const defaultProps = {
    logs: mockLogs,
    totalCount: 2,
    page: 1,
    pageSize: 50,
    totalPages: 1,
    onPageChange: vi.fn(),
    onPageSizeChange: vi.fn(),
    isLoading: false,
  }

  describe('Loading State', () => {
    it('should display loading spinner when isLoading is true', () => {
      render(<AuditLogTable {...defaultProps} logs={[]} isLoading={true} />)

      expect(screen.getByText(/loading audit logs/i)).toBeInTheDocument()
    })

    it('should have spinning animation for loader', () => {
      const { container } = render(
        <AuditLogTable {...defaultProps} logs={[]} isLoading={true} />
      )

      const spinner = container.querySelector('.animate-spin')
      expect(spinner).toBeInTheDocument()
    })

    it('should not display table when loading', () => {
      render(<AuditLogTable {...defaultProps} isLoading={true} />)

      expect(screen.queryByRole('table')).not.toBeInTheDocument()
    })
  })

  describe('Empty State', () => {
    it('should display empty state when no logs', () => {
      render(<AuditLogTable {...defaultProps} logs={[]} totalCount={0} />)

      expect(screen.getByText(/no audit logs found/i)).toBeInTheDocument()
    })

    it('should not display table when empty', () => {
      render(<AuditLogTable {...defaultProps} logs={[]} totalCount={0} />)

      expect(screen.queryByRole('table')).not.toBeInTheDocument()
    })
  })

  describe('Table Headers', () => {
    it('should display all column headers', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('Time')).toBeInTheDocument()
      expect(screen.getByText('Plugin')).toBeInTheDocument()
      expect(screen.getByText('Action')).toBeInTheDocument()
      expect(screen.getByText('Status')).toBeInTheDocument()
      expect(screen.getByText('Account')).toBeInTheDocument()
      expect(screen.getByText('Duration')).toBeInTheDocument()
      expect(screen.getByText('HTTP')).toBeInTheDocument()
      expect(screen.getByText('Error')).toBeInTheDocument()
      expect(screen.getByText('IP')).toBeInTheDocument()
    })
  })

  describe('Table Data Display', () => {
    it('should display plugin IDs', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('bit-bi')).toBeInTheDocument()
      expect(screen.getByText('analytics')).toBeInTheDocument()
    })

    it('should display action type badges', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('Activate')).toBeInTheDocument()
      expect(screen.getByText('Event Failed')).toBeInTheDocument()
    })

    it('should display success status', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('Success')).toBeInTheDocument()
    })

    it('should display failed status', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('Failed')).toBeInTheDocument()
    })

    it('should display truncated account ID', () => {
      render(<AuditLogTable {...defaultProps} />)

      // First 8 chars + "..."
      expect(screen.getByText('acc-1234...')).toBeInTheDocument()
    })

    it('should display dash for null account ID', () => {
      render(<AuditLogTable {...defaultProps} />)

      // Null account IDs should show as "-"
      const dashes = screen.getAllByText('-')
      expect(dashes.length).toBeGreaterThan(0)
    })

    it('should display duration in milliseconds', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('150ms')).toBeInTheDocument()
      expect(screen.getByText('30000ms')).toBeInTheDocument()
    })

    it('should display HTTP status with correct styling', () => {
      render(<AuditLogTable {...defaultProps} />)

      const successStatus = screen.getByText('200')
      expect(successStatus).toHaveClass('bg-green-100')
      expect(successStatus).toHaveClass('text-green-800')

      const errorStatus = screen.getByText('500')
      expect(errorStatus).toHaveClass('bg-red-100')
      expect(errorStatus).toHaveClass('text-red-800')
    })

    it('should display error message', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('Connection timeout')).toBeInTheDocument()
    })

    it('should display IP address', () => {
      render(<AuditLogTable {...defaultProps} />)

      expect(screen.getByText('192.168.1.1')).toBeInTheDocument()
    })

    it('should display formatted date and time', () => {
      const { container } = render(<AuditLogTable {...defaultProps} />)

      // Check for date format (varies by locale, so use getAllByText since there are multiple logs)
      const dateElements = screen.getAllByText(/Dec 15, 2025/i)
      expect(dateElements.length).toBeGreaterThan(0)
      // Check that time column data is rendered (time format HH:mm:ss, timezone-independent)
      const tableContent = container.textContent
      expect(tableContent).toMatch(/\d{2}:\d{2}:\d{2}/)
    })
  })

  describe('Status Icons', () => {
    it('should display CheckCircle icon for success', () => {
      const { container } = render(<AuditLogTable {...defaultProps} />)

      const checkCircle = container.querySelector('.text-green-600')
      expect(checkCircle).toBeInTheDocument()
    })

    it('should display XCircle icon for failure', () => {
      const { container } = render(<AuditLogTable {...defaultProps} />)

      const xCircle = container.querySelector('.text-red-600')
      expect(xCircle).toBeInTheDocument()
    })

    it('should display Clock icon for duration', () => {
      const { container } = render(<AuditLogTable {...defaultProps} />)

      // Clock icons for duration display
      const clockIcons = container.querySelectorAll('[class*="lucide-clock"]')
      expect(clockIcons.length).toBeGreaterThan(0)
    })
  })

  describe('Null Value Handling', () => {
    it('should display dash for null values', () => {
      const logsWithNulls: PluginAuditLogEntry[] = [
        {
          id: 3,
          pluginId: 'test',
          accountId: null,
          clientId: null,
          actionType: 'ACTIVATE',
          requestBodyHash: null,
          requestBodySize: null,
          responseStatus: null,
          success: true,
          errorMessage: null,
          ipAddress: null,
          durationMs: null,
          occurredAt: '2025-12-15T11:00:00Z',
        },
      ]

      render(<AuditLogTable {...defaultProps} logs={logsWithNulls} />)

      // Should display multiple dashes for null values
      const dashes = screen.getAllByText('-')
      expect(dashes.length).toBeGreaterThanOrEqual(4) // account, duration, http, error, ip
    })
  })

  describe('Pagination', () => {
    it('should render pagination component', () => {
      render(<AuditLogTable {...defaultProps} totalPages={5} />)

      // Pagination should be rendered (checking for typical pagination elements)
      // The Pagination component shows "Page {page} of {totalPages}" in both mobile and desktop views
      const pageTexts = screen.getAllByText(/Page 1 of 5/)
      expect(pageTexts.length).toBeGreaterThan(0)
    })

    it('should call onPageChange when page changes', async () => {
      const user = userEvent.setup()
      const onPageChange = vi.fn()

      render(
        <AuditLogTable
          {...defaultProps}
          totalPages={5}
          onPageChange={onPageChange}
        />
      )

      // Find and click next page button by aria-label
      const nextButton = screen.getByRole('button', { name: /next page/i })
      await user.click(nextButton)

      expect(onPageChange).toHaveBeenCalled()
    })

    it('should call onPageSizeChange when page size changes', async () => {
      const user = userEvent.setup()
      const onPageSizeChange = vi.fn()

      render(
        <AuditLogTable
          {...defaultProps}
          onPageSizeChange={onPageSizeChange}
        />
      )

      // Find and interact with page size selector by id
      const pageSizeSelect = screen.getByLabelText(/per page/i)
      await user.selectOptions(pageSizeSelect, '20')

      expect(onPageSizeChange).toHaveBeenCalledWith(20)
    })
  })

  describe('Hover Effects', () => {
    it('should have hover effect on table rows', () => {
      const { container } = render(<AuditLogTable {...defaultProps} />)

      const hoverRow = container.querySelector('.hover\\:bg-gray-50')
      expect(hoverRow).toBeInTheDocument()
    })
  })

  describe('Error Message Styling', () => {
    it('should display error message in red', () => {
      render(<AuditLogTable {...defaultProps} />)

      const errorMessage = screen.getByText('Connection timeout')
      expect(errorMessage).toHaveClass('text-red-600')
    })

    it('should have title attribute for full error message', () => {
      render(<AuditLogTable {...defaultProps} />)

      const errorMessage = screen.getByText('Connection timeout')
      expect(errorMessage).toHaveAttribute('title', 'Connection timeout')
    })
  })
})
