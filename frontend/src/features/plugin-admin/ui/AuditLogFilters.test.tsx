/**
 * Tests for AuditLogFilters Component
 *
 * Tests filter panel toggle, filter inputs, and clear filters functionality.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuditLogFiltersComponent } from './AuditLogFilters'
import type { AuditLogFilters } from '@/entities/plugin/model/types'

describe('AuditLogFiltersComponent', () => {
  const defaultFilters: AuditLogFilters = {
    page: 0,
    size: 50,
  }

  const pluginOptions = ['bit-bi', 'analytics', 'webhook']

  describe('Filter Panel Toggle', () => {
    it('should render Filters button', () => {
      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      expect(screen.getByRole('button', { name: /filters/i })).toBeInTheDocument()
    })

    it('should not show filter panel by default', () => {
      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      expect(screen.queryByText('Filter Options')).not.toBeInTheDocument()
    })

    it('should show filter panel when Filters button is clicked', async () => {
      const user = userEvent.setup()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      expect(screen.getByText('Filter Options')).toBeInTheDocument()
    })

    it('should hide filter panel when Filters button is clicked again', async () => {
      const user = userEvent.setup()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      const filtersButton = screen.getByRole('button', { name: /filters/i })
      await user.click(filtersButton)
      expect(screen.getByText('Filter Options')).toBeInTheDocument()

      await user.click(filtersButton)
      expect(screen.queryByText('Filter Options')).not.toBeInTheDocument()
    })
  })

  describe('Active Filters Badge', () => {
    it('should show "Active" badge when filters are applied', () => {
      const filtersWithPlugin: AuditLogFilters = {
        ...defaultFilters,
        pluginId: 'bit-bi',
      }

      render(
        <AuditLogFiltersComponent
          filters={filtersWithPlugin}
          onFiltersChange={vi.fn()}
        />
      )

      expect(screen.getByText('Active')).toBeInTheDocument()
    })

    it('should not show "Active" badge when no filters are applied', () => {
      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      expect(screen.queryByText('Active')).not.toBeInTheDocument()
    })

    it('should show "Active" badge for any active filter', () => {
      const testCases: AuditLogFilters[] = [
        { ...defaultFilters, pluginId: 'bit-bi' },
        { ...defaultFilters, accountId: 'acc-123' },
        { ...defaultFilters, actionType: 'ACTIVATE' },
        { ...defaultFilters, success: true },
        { ...defaultFilters, from: '2025-12-01T00:00:00Z' },
        { ...defaultFilters, to: '2025-12-31T23:59:59Z' },
      ]

      testCases.forEach((filters) => {
        const { unmount } = render(
          <AuditLogFiltersComponent
            filters={filters}
            onFiltersChange={vi.fn()}
          />
        )

        expect(screen.getByText('Active')).toBeInTheDocument()
        unmount()
      })
    })
  })

  describe('Filter Inputs', () => {
    it('should render all filter inputs when panel is open', async () => {
      const user = userEvent.setup()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          pluginOptions={pluginOptions}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      expect(screen.getByLabelText(/plugin/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/account id/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/action type/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/status/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/from date/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/to date/i)).toBeInTheDocument()
    })

    it('should render plugin options in dropdown', async () => {
      const user = userEvent.setup()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          pluginOptions={pluginOptions}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      const pluginSelect = screen.getByLabelText(/plugin/i)
      expect(pluginSelect).toContainHTML('bit-bi')
      expect(pluginSelect).toContainHTML('analytics')
      expect(pluginSelect).toContainHTML('webhook')
    })

    it('should render action type options in dropdown', async () => {
      const user = userEvent.setup()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      const actionSelect = screen.getByLabelText(/action type/i)
      expect(actionSelect).toContainHTML('Activate')
      expect(actionSelect).toContainHTML('Deactivate')
      expect(actionSelect).toContainHTML('Reactivate')
      expect(actionSelect).toContainHTML('Event Dispatched')
      expect(actionSelect).toContainHTML('Event Failed')
      expect(actionSelect).toContainHTML('Event Timeout')
    })
  })

  describe('Filter Changes', () => {
    it('should call onFiltersChange when plugin filter changes', async () => {
      const user = userEvent.setup()
      const onFiltersChange = vi.fn()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          pluginOptions={pluginOptions}
          onFiltersChange={onFiltersChange}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))
      await user.selectOptions(screen.getByLabelText(/plugin/i), 'bit-bi')

      expect(onFiltersChange).toHaveBeenCalledWith({
        ...defaultFilters,
        pluginId: 'bit-bi',
        page: 0, // Reset to first page
      })
    })

    it('should call onFiltersChange when account ID changes', async () => {
      const user = userEvent.setup()
      const onFiltersChange = vi.fn()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={onFiltersChange}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))
      await user.type(screen.getByLabelText(/account id/i), 'acc-123')

      // Check that onFiltersChange was called (once per character typed)
      expect(onFiltersChange).toHaveBeenCalled()
      // The final state should include accountId (check that it was set at some point)
      const allCalls = onFiltersChange.mock.calls
      const lastCall = allCalls[allCalls.length - 1][0]
      // After typing 'acc-123', the value should be building up
      expect(lastCall).toHaveProperty('accountId')
      expect(lastCall.page).toBe(0) // Reset to first page
    })

    it('should call onFiltersChange when action type changes', async () => {
      const user = userEvent.setup()
      const onFiltersChange = vi.fn()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={onFiltersChange}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))
      await user.selectOptions(screen.getByLabelText(/action type/i), 'ACTIVATE')

      expect(onFiltersChange).toHaveBeenCalledWith({
        ...defaultFilters,
        actionType: 'ACTIVATE',
        page: 0,
      })
    })

    it('should call onFiltersChange when success status changes', async () => {
      const user = userEvent.setup()
      const onFiltersChange = vi.fn()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={onFiltersChange}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))
      await user.selectOptions(screen.getByLabelText(/status/i), 'true')

      expect(onFiltersChange).toHaveBeenCalledWith({
        ...defaultFilters,
        success: true,
        page: 0,
      })
    })
  })

  describe('Clear Filters', () => {
    it('should show "Clear all filters" button when filters are active', async () => {
      const user = userEvent.setup()

      const filtersWithPlugin: AuditLogFilters = {
        ...defaultFilters,
        pluginId: 'bit-bi',
      }

      render(
        <AuditLogFiltersComponent
          filters={filtersWithPlugin}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      expect(screen.getByText(/clear all filters/i)).toBeInTheDocument()
    })

    it('should not show "Clear all filters" button when no filters are active', async () => {
      const user = userEvent.setup()

      render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      expect(screen.queryByText(/clear all filters/i)).not.toBeInTheDocument()
    })

    it('should clear all filters when "Clear all filters" is clicked', async () => {
      const user = userEvent.setup()
      const onFiltersChange = vi.fn()

      const filtersWithMultiple: AuditLogFilters = {
        page: 2,
        size: 25,
        pluginId: 'bit-bi',
        accountId: 'acc-123',
        actionType: 'ACTIVATE',
      }

      render(
        <AuditLogFiltersComponent
          filters={filtersWithMultiple}
          onFiltersChange={onFiltersChange}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))
      await user.click(screen.getByText(/clear all filters/i))

      expect(onFiltersChange).toHaveBeenCalledWith({
        page: 2,
        size: 25,
      })
    })
  })

  describe('Filter Panel Styling', () => {
    it('should have proper grid layout for filters', async () => {
      const user = userEvent.setup()

      const { container } = render(
        <AuditLogFiltersComponent
          filters={defaultFilters}
          onFiltersChange={vi.fn()}
        />
      )

      await user.click(screen.getByRole('button', { name: /filters/i }))

      const grid = container.querySelector('.grid')
      expect(grid).toBeInTheDocument()
      expect(grid).toHaveClass('sm:grid-cols-2')
      expect(grid).toHaveClass('lg:grid-cols-3')
    })
  })
})
