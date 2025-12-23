/**
 * Tests for PluginCard Component
 *
 * Tests plugin card display, status badges, and action buttons.
 *
 * Feature: My Plugins Dashboard Widget
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PluginCard } from '../PluginCard'
import type { AccountPluginSummary, AvailablePlugin } from '../../model/types'

describe('PluginCard', () => {
  const mockActivePlugin: AccountPluginSummary = {
    pluginId: 'bit-bi',
    pluginName: 'Bit BI',
    isActive: true,
    activatedAt: '2025-12-01T10:00:00Z',
    deactivatedAt: null,
    lastUsedAt: '2025-12-20T15:30:00Z',
  }

  const mockInactivePlugin: AccountPluginSummary = {
    pluginId: 'analytics',
    pluginName: 'Analytics',
    isActive: false,
    activatedAt: '2025-11-01T10:00:00Z',
    deactivatedAt: '2025-12-15T10:00:00Z',
    lastUsedAt: '2025-12-10T15:30:00Z',
  }

  const mockAvailablePlugin: AvailablePlugin = {
    pluginId: 'bit-bi',
    displayName: 'Bit BI',
    version: '1.0.0',
    isEnabled: true,
  }

  describe('Display', () => {
    it('should render plugin name', () => {
      render(<PluginCard plugin={mockActivePlugin} />)

      expect(screen.getByText('Bit BI')).toBeInTheDocument()
    })

    it('should render version when availablePlugin is provided', () => {
      render(<PluginCard availablePlugin={mockAvailablePlugin} />)

      expect(screen.getByText('v1.0.0')).toBeInTheDocument()
    })

    it('should show Active badge for active plugins', () => {
      render(<PluginCard plugin={mockActivePlugin} />)

      expect(screen.getByText('Active')).toBeInTheDocument()
    })

    it('should show Inactive badge for inactive plugins', () => {
      render(<PluginCard plugin={mockInactivePlugin} />)

      expect(screen.getByText('Inactive')).toBeInTheDocument()
    })

    it('should display activated date', () => {
      render(<PluginCard plugin={mockActivePlugin} />)

      expect(screen.getByText(/activated/i)).toBeInTheDocument()
    })

    it('should display last used date when available', () => {
      render(<PluginCard plugin={mockActivePlugin} />)

      expect(screen.getByText(/last used/i)).toBeInTheDocument()
    })
  })

  describe('Active Plugin Actions', () => {
    it('should show Deactivate button for active plugins', () => {
      render(<PluginCard plugin={mockActivePlugin} />)

      expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument()
    })

    it('should call onDeactivate when Deactivate button is clicked', async () => {
      const user = userEvent.setup()
      const onDeactivate = vi.fn()

      render(<PluginCard plugin={mockActivePlugin} onDeactivate={onDeactivate} />)

      await user.click(screen.getByRole('button', { name: 'Deactivate' }))

      expect(onDeactivate).toHaveBeenCalledWith('bit-bi')
    })

    it('should disable button and show loading text when isPending', () => {
      render(<PluginCard plugin={mockActivePlugin} isPending={true} />)

      const button = screen.getByRole('button', { name: 'Deactivating...' })
      expect(button).toBeDisabled()
    })
  })

  describe('Inactive Plugin Actions', () => {
    it('should show Activate button for inactive plugins', () => {
      render(<PluginCard plugin={mockInactivePlugin} />)

      expect(screen.getByRole('button', { name: 'Activate' })).toBeInTheDocument()
    })

    it('should call onActivate when Activate button is clicked', async () => {
      const user = userEvent.setup()
      const onActivate = vi.fn()

      render(<PluginCard plugin={mockInactivePlugin} onActivate={onActivate} />)

      await user.click(screen.getByRole('button', { name: 'Activate' }))

      expect(onActivate).toHaveBeenCalledWith('analytics')
    })

    it('should disable button and show loading text when isPending', () => {
      render(<PluginCard plugin={mockInactivePlugin} isPending={true} />)

      const button = screen.getByRole('button', { name: 'Activating...' })
      expect(button).toBeDisabled()
    })
  })

  describe('Available Plugin (Not Yet Activated)', () => {
    it('should show Activate button when isActivated is false', () => {
      render(<PluginCard availablePlugin={mockAvailablePlugin} isActivated={false} />)

      expect(screen.getByRole('button', { name: 'Activate' })).toBeInTheDocument()
    })

    it('should use displayName from availablePlugin', () => {
      render(<PluginCard availablePlugin={mockAvailablePlugin} />)

      expect(screen.getByText('Bit BI')).toBeInTheDocument()
    })
  })
})
