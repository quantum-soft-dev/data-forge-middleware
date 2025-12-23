/**
 * Tests for PluginList Component
 *
 * Tests list rendering, loading states, error states, and empty states.
 *
 * Feature: My Plugins Dashboard Widget
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PluginList } from '../PluginList'
import type { AccountPluginSummary, AvailablePlugin } from '../../model/types'

describe('PluginList', () => {
  const mockActivePlugins: AccountPluginSummary[] = [
    {
      pluginId: 'bit-bi',
      pluginName: 'Bit BI',
      isActive: true,
      activatedAt: '2025-12-01T10:00:00Z',
      deactivatedAt: null,
      lastUsedAt: '2025-12-20T15:30:00Z',
    },
  ]

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
    {
      pluginId: 'disabled-plugin',
      displayName: 'Disabled Plugin',
      version: '1.0.0',
      isEnabled: false,
    },
  ]

  describe('Loading State', () => {
    it('should show loading spinner when isLoading is true', () => {
      render(<PluginList plugins={[]} isLoading={true} />)

      expect(screen.getByText('Loading plugins...')).toBeInTheDocument()
    })

    it('should not render plugins when loading', () => {
      render(<PluginList plugins={mockActivePlugins} isLoading={true} />)

      expect(screen.queryByText('Bit BI')).not.toBeInTheDocument()
    })
  })

  describe('Error State', () => {
    it('should show error message when error is provided', () => {
      const error = new Error('Network error')

      render(<PluginList plugins={[]} error={error} />)

      expect(screen.getByText(/failed to load plugins/i)).toBeInTheDocument()
      expect(screen.getByText(/network error/i)).toBeInTheDocument()
    })
  })

  describe('Empty State', () => {
    it('should show empty state when no plugins available', () => {
      render(<PluginList plugins={[]} availablePlugins={[]} />)

      expect(screen.getByText('No plugins available')).toBeInTheDocument()
    })

    it('should show empty state when all available plugins are disabled', () => {
      const disabledOnlyPlugins: AvailablePlugin[] = [
        {
          pluginId: 'disabled',
          displayName: 'Disabled',
          version: '1.0.0',
          isEnabled: false,
        },
      ]

      render(<PluginList plugins={[]} availablePlugins={disabledOnlyPlugins} />)

      expect(screen.getByText('No plugins available')).toBeInTheDocument()
    })
  })

  describe('Plugin List Display', () => {
    it('should render activated plugins', () => {
      render(<PluginList plugins={mockActivePlugins} />)

      expect(screen.getByText('Bit BI')).toBeInTheDocument()
    })

    it('should render unactivated available plugins', () => {
      render(<PluginList plugins={[]} availablePlugins={mockAvailablePlugins} />)

      // bit-bi and analytics should be shown (enabled)
      expect(screen.getByText('Bit BI')).toBeInTheDocument()
      expect(screen.getByText('Analytics')).toBeInTheDocument()
      // disabled plugin should not be shown
      expect(screen.queryByText('Disabled Plugin')).not.toBeInTheDocument()
    })

    it('should not show duplicate plugins (activated + available)', () => {
      render(
        <PluginList
          plugins={mockActivePlugins}
          availablePlugins={mockAvailablePlugins}
        />
      )

      // bit-bi appears once (activated version takes precedence)
      const bitBiElements = screen.getAllByText('Bit BI')
      expect(bitBiElements).toHaveLength(1)

      // analytics appears once (unactivated available)
      expect(screen.getByText('Analytics')).toBeInTheDocument()
    })

    it('should show activated plugins before unactivated ones', () => {
      render(
        <PluginList
          plugins={mockActivePlugins}
          availablePlugins={mockAvailablePlugins}
        />
      )

      const pluginCards = screen.getAllByRole('button')
      // First should be Deactivate (for active plugin)
      expect(pluginCards[0]).toHaveTextContent('Deactivate')
      // Second should be Activate (for unactivated plugin)
      expect(pluginCards[1]).toHaveTextContent('Activate')
    })
  })

  describe('Interactions', () => {
    it('should call onActivate when Activate button is clicked', async () => {
      const user = userEvent.setup()
      const onActivate = vi.fn()

      render(
        <PluginList
          plugins={[]}
          availablePlugins={mockAvailablePlugins}
          onActivate={onActivate}
        />
      )

      await user.click(screen.getAllByRole('button', { name: 'Activate' })[0])

      expect(onActivate).toHaveBeenCalledWith('bit-bi')
    })

    it('should call onDeactivate when Deactivate button is clicked', async () => {
      const user = userEvent.setup()
      const onDeactivate = vi.fn()

      render(
        <PluginList plugins={mockActivePlugins} onDeactivate={onDeactivate} />
      )

      await user.click(screen.getByRole('button', { name: 'Deactivate' }))

      expect(onDeactivate).toHaveBeenCalledWith('bit-bi')
    })

    it('should show pending state for plugins being processed', () => {
      const pendingPluginIds = new Set(['bit-bi'])

      render(
        <PluginList
          plugins={mockActivePlugins}
          pendingPluginIds={pendingPluginIds}
        />
      )

      expect(screen.getByRole('button', { name: 'Deactivating...' })).toBeDisabled()
    })
  })

  describe('Grid Layout', () => {
    it('should use grid layout for cards', () => {
      const { container } = render(
        <PluginList plugins={mockActivePlugins} availablePlugins={mockAvailablePlugins} />
      )

      const grid = container.querySelector('.grid')
      expect(grid).toBeInTheDocument()
      expect(grid).toHaveClass('sm:grid-cols-2')
      expect(grid).toHaveClass('lg:grid-cols-3')
    })
  })
})
