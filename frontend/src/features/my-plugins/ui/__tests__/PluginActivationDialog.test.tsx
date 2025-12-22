/**
 * Tests for PluginActivationDialog Component
 *
 * Tests dialog rendering, form validation, and submission.
 *
 * Feature: My Plugins Dashboard Widget
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PluginActivationDialog } from '../PluginActivationDialog'
import type { AvailablePlugin } from '../../model/types'

describe('PluginActivationDialog', () => {
  const mockBitBiPlugin: AvailablePlugin = {
    pluginId: 'bit-bi',
    displayName: 'Bit BI',
    version: '1.0.0',
    isEnabled: true,
  }

  const mockOtherPlugin: AvailablePlugin = {
    pluginId: 'analytics',
    displayName: 'Analytics',
    version: '2.0.0',
    isEnabled: true,
  }

  const defaultProps = {
    open: true,
    plugin: mockBitBiPlugin,
    onClose: vi.fn(),
    onSubmit: vi.fn(),
  }

  describe('Rendering', () => {
    it('should render dialog when open is true', () => {
      render(<PluginActivationDialog {...defaultProps} />)

      expect(screen.getByText('Activate Bit BI')).toBeInTheDocument()
    })

    it('should not render dialog when open is false', () => {
      render(<PluginActivationDialog {...defaultProps} open={false} />)

      expect(screen.queryByText('Activate Bit BI')).not.toBeInTheDocument()
    })

    it('should not render when plugin is null', () => {
      render(<PluginActivationDialog {...defaultProps} plugin={null} />)

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  describe('Bit BI Plugin Form', () => {
    it('should show tenantId field for bit-bi plugin', () => {
      render(<PluginActivationDialog {...defaultProps} />)

      expect(screen.getByLabelText(/tenant id/i)).toBeInTheDocument()
    })

    it('should show description about Bit BI tenant ID', () => {
      render(<PluginActivationDialog {...defaultProps} />)

      expect(screen.getByText(/enter your bit bi tenant id/i)).toBeInTheDocument()
    })

    it('should validate tenantId is required', async () => {
      const user = userEvent.setup()
      const onSubmit = vi.fn()

      render(<PluginActivationDialog {...defaultProps} onSubmit={onSubmit} />)

      // Submit without entering tenantId
      await user.click(screen.getByRole('button', { name: 'Activate' }))

      // Should show error message
      expect(screen.getByText('Tenant ID is required')).toBeInTheDocument()
      expect(onSubmit).not.toHaveBeenCalled()
    })

    it('should clear error when user types', async () => {
      const user = userEvent.setup()

      render(<PluginActivationDialog {...defaultProps} />)

      // Submit without entering tenantId
      await user.click(screen.getByRole('button', { name: 'Activate' }))
      expect(screen.getByText('Tenant ID is required')).toBeInTheDocument()

      // Start typing
      await user.type(screen.getByLabelText(/tenant id/i), 't')

      // Error should be cleared
      expect(screen.queryByText('Tenant ID is required')).not.toBeInTheDocument()
    })

    it('should call onSubmit with tenantId data', async () => {
      const user = userEvent.setup()
      const onSubmit = vi.fn()

      render(<PluginActivationDialog {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByLabelText(/tenant id/i), 'my-tenant-123')
      await user.click(screen.getByRole('button', { name: 'Activate' }))

      expect(onSubmit).toHaveBeenCalledWith('bit-bi', {
        pluginData: { tenantId: 'my-tenant-123' },
      })
    })

    it('should trim tenantId before submission', async () => {
      const user = userEvent.setup()
      const onSubmit = vi.fn()

      render(<PluginActivationDialog {...defaultProps} onSubmit={onSubmit} />)

      await user.type(screen.getByLabelText(/tenant id/i), '  my-tenant  ')
      await user.click(screen.getByRole('button', { name: 'Activate' }))

      expect(onSubmit).toHaveBeenCalledWith('bit-bi', {
        pluginData: { tenantId: 'my-tenant' },
      })
    })
  })

  describe('Other Plugin Form', () => {
    it('should not show tenantId field for non-bit-bi plugins', () => {
      render(<PluginActivationDialog {...defaultProps} plugin={mockOtherPlugin} />)

      expect(screen.queryByLabelText(/tenant id/i)).not.toBeInTheDocument()
    })

    it('should show generic activation message', () => {
      render(<PluginActivationDialog {...defaultProps} plugin={mockOtherPlugin} />)

      expect(screen.getByText(/activate analytics for your account/i)).toBeInTheDocument()
    })

    it('should call onSubmit with empty pluginData', async () => {
      const user = userEvent.setup()
      const onSubmit = vi.fn()

      render(
        <PluginActivationDialog
          {...defaultProps}
          plugin={mockOtherPlugin}
          onSubmit={onSubmit}
        />
      )

      await user.click(screen.getByRole('button', { name: 'Activate' }))

      expect(onSubmit).toHaveBeenCalledWith('analytics', {
        pluginData: {},
      })
    })
  })

  describe('Loading State', () => {
    it('should show Activating... when isLoading', () => {
      render(<PluginActivationDialog {...defaultProps} isLoading={true} />)

      expect(screen.getByRole('button', { name: 'Activating...' })).toBeInTheDocument()
    })

    it('should disable buttons when isLoading', () => {
      render(<PluginActivationDialog {...defaultProps} isLoading={true} />)

      expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'Activating...' })).toBeDisabled()
    })

    it('should disable input when isLoading', () => {
      render(<PluginActivationDialog {...defaultProps} isLoading={true} />)

      expect(screen.getByLabelText(/tenant id/i)).toBeDisabled()
    })
  })

  describe('Cancel Behavior', () => {
    it('should call onClose when Cancel is clicked', async () => {
      const user = userEvent.setup()
      const onClose = vi.fn()

      render(<PluginActivationDialog {...defaultProps} onClose={onClose} />)

      await user.click(screen.getByRole('button', { name: 'Cancel' }))

      expect(onClose).toHaveBeenCalled()
    })

    it('should call onClose when dialog is dismissed', async () => {
      const onClose = vi.fn()

      render(<PluginActivationDialog {...defaultProps} onClose={onClose} />)

      // Press Escape key to close dialog
      fireEvent.keyDown(document, { key: 'Escape' })

      expect(onClose).toHaveBeenCalled()
    })
  })
})
