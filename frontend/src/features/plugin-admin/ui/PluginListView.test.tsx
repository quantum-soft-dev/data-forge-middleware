/**
 * Tests for PluginListView Component
 *
 * Tests plugin cards grid display, loading states, empty states,
 * and click interactions.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PluginListView } from './PluginListView'
import type { PluginConfig } from '@/entities/plugin/model/types'

describe('PluginListView', () => {
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

  describe('Loading State', () => {
    it('should display loading spinner when isLoading is true', () => {
      render(<PluginListView plugins={[]} isLoading={true} />)

      expect(screen.getByText(/loading plugins/i)).toBeInTheDocument()
    })

    it('should have spinning animation for loader', () => {
      const { container } = render(<PluginListView plugins={[]} isLoading={true} />)

      const spinner = container.querySelector('.animate-spin')
      expect(spinner).toBeInTheDocument()
    })

    it('should not display plugins when loading', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={true} />)

      expect(screen.queryByText('Bit BI Integration')).not.toBeInTheDocument()
    })
  })

  describe('Empty State', () => {
    it('should display empty state when no plugins', () => {
      render(<PluginListView plugins={[]} isLoading={false} />)

      expect(screen.getByText(/no plugins registered/i)).toBeInTheDocument()
    })

    it('should not display empty state when plugins exist', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      expect(screen.queryByText(/no plugins registered/i)).not.toBeInTheDocument()
    })
  })

  describe('Plugin Cards Display', () => {
    it('should render all plugins', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      expect(screen.getByText('Bit BI Integration')).toBeInTheDocument()
      expect(screen.getByText('Analytics Plugin')).toBeInTheDocument()
    })

    it('should display plugin IDs', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      expect(screen.getByText('bit-bi')).toBeInTheDocument()
      expect(screen.getByText('analytics')).toBeInTheDocument()
    })

    it('should display plugin versions', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      expect(screen.getByText(/version: 1\.0\.0/i)).toBeInTheDocument()
      expect(screen.getByText(/version: 2\.1\.0/i)).toBeInTheDocument()
    })

    it('should display status badges', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      expect(screen.getByText('Enabled')).toBeInTheDocument()
      expect(screen.getByText('Disabled')).toBeInTheDocument()
    })

    it('should display created date (relative)', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      // Check that creation date text exists (format may vary)
      // Use getAllByText since there are multiple plugins with created dates
      const createdElements = screen.getAllByText(/created/i)
      expect(createdElements.length).toBeGreaterThan(0)
    })

    it('should display supported events', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      // Use getAllByText since there are multiple plugins with "Supported Events:" label
      const supportedEventsLabels = screen.getAllByText('Supported Events:')
      expect(supportedEventsLabels.length).toBeGreaterThan(0)
      expect(screen.getAllByText('BATCH_COMPLETED')).toHaveLength(2)
      expect(screen.getByText('FILE_UPLOADED')).toBeInTheDocument()
    })
  })

  describe('Click Interactions', () => {
    it('should call onPluginClick when Audit Logs button is clicked', async () => {
      const user = userEvent.setup()
      const onPluginClick = vi.fn()

      render(
        <PluginListView
          plugins={mockPlugins}
          isLoading={false}
          onPluginClick={onPluginClick}
        />
      )

      // Find the first Audit Logs button (for bit-bi plugin)
      const auditButtons = screen.getAllByRole('button', { name: /audit logs/i })
      await user.click(auditButtons[0])

      expect(onPluginClick).toHaveBeenCalledWith('bit-bi')
    })

    it('should call onHistoryClick when SQL History button is clicked', async () => {
      const user = userEvent.setup()
      const onHistoryClick = vi.fn()

      render(
        <PluginListView
          plugins={mockPlugins}
          isLoading={false}
          onHistoryClick={onHistoryClick}
        />
      )

      // Find the first SQL History button (for bit-bi plugin)
      const historyButtons = screen.getAllByRole('button', { name: /sql history/i })
      await user.click(historyButtons[0])

      expect(onHistoryClick).toHaveBeenCalledWith('bit-bi')
    })

    it('should render Audit Logs button when onPluginClick is provided', () => {
      const onPluginClick = vi.fn()

      render(
        <PluginListView
          plugins={mockPlugins}
          isLoading={false}
          onPluginClick={onPluginClick}
        />
      )

      const buttons = screen.getAllByRole('button', { name: /audit logs/i })
      expect(buttons).toHaveLength(2) // One for each plugin
    })

    it('should render SQL History button when onHistoryClick is provided', () => {
      const onHistoryClick = vi.fn()

      render(
        <PluginListView
          plugins={mockPlugins}
          isLoading={false}
          onHistoryClick={onHistoryClick}
        />
      )

      const buttons = screen.getAllByRole('button', { name: /sql history/i })
      expect(buttons).toHaveLength(2) // One for each plugin
    })

    it('should not render action buttons when no callbacks provided', () => {
      render(<PluginListView plugins={mockPlugins} isLoading={false} />)

      expect(screen.queryByRole('button', { name: /audit logs/i })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /sql history/i })).not.toBeInTheDocument()
    })
  })

  describe('Grid Layout', () => {
    it('should have grid layout for cards', () => {
      const { container } = render(
        <PluginListView plugins={mockPlugins} isLoading={false} />
      )

      const grid = container.querySelector('.grid')
      expect(grid).toBeInTheDocument()
      expect(grid).toHaveClass('sm:grid-cols-2')
      expect(grid).toHaveClass('lg:grid-cols-3')
    })
  })

  describe('Plugin Without Events', () => {
    it('should not show Supported Events section when empty', () => {
      const pluginWithoutEvents: PluginConfig[] = [
        {
          pluginId: 'empty-plugin',
          displayName: 'Empty Plugin',
          version: '1.0.0',
          isEnabled: true,
          supportedEvents: [],
          createdAt: '2025-12-01T10:00:00Z',
          updatedAt: '2025-12-01T10:00:00Z',
        },
      ]

      render(<PluginListView plugins={pluginWithoutEvents} isLoading={false} />)

      expect(screen.queryByText('Supported Events:')).not.toBeInTheDocument()
    })
  })

  describe('Hover Effects', () => {
    it('should have hover shadow effect', () => {
      const { container } = render(
        <PluginListView plugins={mockPlugins} isLoading={false} />
      )

      const card = container.querySelector('.hover\\:shadow-md')
      expect(card).toBeInTheDocument()
    })
  })
})
