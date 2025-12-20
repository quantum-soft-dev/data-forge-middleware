/**
 * Tests for ActionTypeBadge Component
 *
 * Tests badge rendering for different action types with correct labels and colors.
 *
 * Feature: 013-plugin-system (Admin UI Phase 9)
 */

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ActionTypeBadge } from './ActionTypeBadge'
import type { PluginActionType } from '../model/types'

describe('ActionTypeBadge', () => {
  describe('Action Type Labels', () => {
    const testCases: { actionType: PluginActionType; expectedLabel: string }[] = [
      { actionType: 'ACTIVATE', expectedLabel: 'Activate' },
      { actionType: 'DEACTIVATE', expectedLabel: 'Deactivate' },
      { actionType: 'REACTIVATE', expectedLabel: 'Reactivate' },
      { actionType: 'EVENT_DISPATCHED', expectedLabel: 'Event Dispatched' },
      { actionType: 'EVENT_FAILED', expectedLabel: 'Event Failed' },
      { actionType: 'EVENT_TIMEOUT', expectedLabel: 'Event Timeout' },
    ]

    testCases.forEach(({ actionType, expectedLabel }) => {
      it(`should render "${expectedLabel}" for ${actionType}`, () => {
        render(<ActionTypeBadge actionType={actionType} />)

        expect(screen.getByText(expectedLabel)).toBeInTheDocument()
      })
    })
  })

  describe('Action Type Colors', () => {
    it('should have green styling for ACTIVATE', () => {
      render(<ActionTypeBadge actionType="ACTIVATE" />)

      const badge = screen.getByText('Activate')
      expect(badge).toHaveClass('bg-green-100')
      expect(badge).toHaveClass('text-green-800')
    })

    it('should have yellow styling for DEACTIVATE', () => {
      render(<ActionTypeBadge actionType="DEACTIVATE" />)

      const badge = screen.getByText('Deactivate')
      expect(badge).toHaveClass('bg-yellow-100')
      expect(badge).toHaveClass('text-yellow-800')
    })

    it('should have blue styling for REACTIVATE', () => {
      render(<ActionTypeBadge actionType="REACTIVATE" />)

      const badge = screen.getByText('Reactivate')
      expect(badge).toHaveClass('bg-blue-100')
      expect(badge).toHaveClass('text-blue-800')
    })

    it('should have purple styling for EVENT_DISPATCHED', () => {
      render(<ActionTypeBadge actionType="EVENT_DISPATCHED" />)

      const badge = screen.getByText('Event Dispatched')
      expect(badge).toHaveClass('bg-purple-100')
      expect(badge).toHaveClass('text-purple-800')
    })

    it('should have red styling for EVENT_FAILED', () => {
      render(<ActionTypeBadge actionType="EVENT_FAILED" />)

      const badge = screen.getByText('Event Failed')
      expect(badge).toHaveClass('bg-red-100')
      expect(badge).toHaveClass('text-red-800')
    })

    it('should have orange styling for EVENT_TIMEOUT', () => {
      render(<ActionTypeBadge actionType="EVENT_TIMEOUT" />)

      const badge = screen.getByText('Event Timeout')
      expect(badge).toHaveClass('bg-orange-100')
      expect(badge).toHaveClass('text-orange-800')
    })
  })

  describe('Custom className', () => {
    it('should apply custom className when provided', () => {
      render(<ActionTypeBadge actionType="ACTIVATE" className="custom-class" />)

      const badge = screen.getByText('Activate')
      expect(badge).toHaveClass('custom-class')
    })

    it('should preserve default classes when custom className is added', () => {
      render(<ActionTypeBadge actionType="ACTIVATE" className="custom-class" />)

      const badge = screen.getByText('Activate')
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
      render(<ActionTypeBadge actionType="ACTIVATE" />)

      const badge = screen.getByText('Activate')
      expect(badge).toHaveClass('inline-flex')
      expect(badge).toHaveClass('items-center')
      expect(badge).toHaveClass('rounded-full')
    })
  })
})
