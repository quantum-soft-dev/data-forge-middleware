/**
 * Tests for PluginStatusBadge Component
 *
 * Tests badge rendering, styling, and accessibility.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PluginStatusBadge } from './PluginStatusBadge'

describe('PluginStatusBadge', () => {
  describe('Enabled State', () => {
    it('should render "Enabled" text when isEnabled is true', () => {
      render(<PluginStatusBadge isEnabled={true} />)

      expect(screen.getByText('Enabled')).toBeInTheDocument()
    })

    it('should have green styling when isEnabled is true', () => {
      render(<PluginStatusBadge isEnabled={true} />)

      const badge = screen.getByText('Enabled')
      expect(badge).toHaveClass('bg-green-100')
      expect(badge).toHaveClass('text-green-800')
    })

    it('should have correct aria-label for enabled state', () => {
      render(<PluginStatusBadge isEnabled={true} />)

      const badge = screen.getByLabelText('Plugin status: Enabled')
      expect(badge).toBeInTheDocument()
    })
  })

  describe('Disabled State', () => {
    it('should render "Disabled" text when isEnabled is false', () => {
      render(<PluginStatusBadge isEnabled={false} />)

      expect(screen.getByText('Disabled')).toBeInTheDocument()
    })

    it('should have gray styling when isEnabled is false', () => {
      render(<PluginStatusBadge isEnabled={false} />)

      const badge = screen.getByText('Disabled')
      expect(badge).toHaveClass('bg-gray-100')
      expect(badge).toHaveClass('text-gray-800')
    })

    it('should have correct aria-label for disabled state', () => {
      render(<PluginStatusBadge isEnabled={false} />)

      const badge = screen.getByLabelText('Plugin status: Disabled')
      expect(badge).toBeInTheDocument()
    })
  })

  describe('Custom className', () => {
    it('should apply custom className when provided', () => {
      render(<PluginStatusBadge isEnabled={true} className="custom-class" />)

      const badge = screen.getByText('Enabled')
      expect(badge).toHaveClass('custom-class')
    })

    it('should preserve default classes when custom className is added', () => {
      render(<PluginStatusBadge isEnabled={true} className="custom-class" />)

      const badge = screen.getByText('Enabled')
      expect(badge).toHaveClass('inline-flex')
      expect(badge).toHaveClass('rounded-full')
      expect(badge).toHaveClass('px-2.5')
      expect(badge).toHaveClass('py-0.5')
      expect(badge).toHaveClass('text-xs')
      expect(badge).toHaveClass('font-medium')
    })
  })

  describe('Base Styling', () => {
    it('should always have base badge styling classes', () => {
      render(<PluginStatusBadge isEnabled={true} />)

      const badge = screen.getByText('Enabled')
      expect(badge).toHaveClass('inline-flex')
      expect(badge).toHaveClass('items-center')
      expect(badge).toHaveClass('rounded-full')
    })
  })
})
