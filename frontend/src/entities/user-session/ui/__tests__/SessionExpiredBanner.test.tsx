import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SessionExpiredBanner } from '../SessionExpiredBanner'
import * as sessionExpiry from '@/shared/lib/auth/session-expiry'

// Mock session expiry module
vi.mock('@/shared/lib/auth/session-expiry', () => ({
  getSessionExpired: vi.fn(),
  clearSessionExpired: vi.fn(),
}))

describe('SessionExpiredBanner', () => {
  const mockGetSessionExpired = vi.mocked(sessionExpiry.getSessionExpired)
  const mockClearSessionExpired = vi.mocked(sessionExpiry.clearSessionExpired)

  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // T026: Test renders message when expired
  it('should render expiry message when session is expired', () => {
    mockGetSessionExpired.mockReturnValue({
      isExpired: true,
      reason: 'refresh_token_expired',
      timestamp: new Date().toISOString(),
    })

    render(<SessionExpiredBanner />)

    expect(
      screen.getByText(/your session has expired due to inactivity/i)
    ).toBeInTheDocument()
    expect(screen.getByText(/please log in again/i)).toBeInTheDocument()
  })

  // T027: Test does not render when not expired
  it('should not render when session is not expired', () => {
    mockGetSessionExpired.mockReturnValue(null)

    const { container } = render(<SessionExpiredBanner />)

    expect(container).toBeEmptyDOMElement()
  })

  // T028: Test clears state after displaying
  it('should clear session expired state after displaying', () => {
    mockGetSessionExpired.mockReturnValue({
      isExpired: true,
      reason: 'refresh_token_expired',
      timestamp: new Date().toISOString(),
    })

    render(<SessionExpiredBanner />)

    // clearSessionExpired should be called after rendering
    expect(mockClearSessionExpired).toHaveBeenCalledTimes(1)
  })

  // T033: Test ARIA attributes for accessibility
  it('should have correct ARIA attributes for accessibility', () => {
    mockGetSessionExpired.mockReturnValue({
      isExpired: true,
      reason: 'refresh_token_expired',
      timestamp: new Date().toISOString(),
    })

    render(<SessionExpiredBanner />)

    const alert = screen.getByRole('alert')
    expect(alert).toBeInTheDocument()
    expect(alert).toHaveAttribute('aria-live', 'polite')
  })

  // Test: Different reasons render appropriately
  it('should render for manual_logout reason', () => {
    mockGetSessionExpired.mockReturnValue({
      isExpired: true,
      reason: 'manual_logout',
      timestamp: new Date().toISOString(),
    })

    const { container } = render(<SessionExpiredBanner />)

    // Manual logout should not show session expiry banner
    // (user intentionally logged out, not due to inactivity)
    expect(container).toBeEmptyDOMElement()
  })

  // Test: isExpired false should not render
  it('should not render when isExpired is false even with data', () => {
    mockGetSessionExpired.mockReturnValue({
      isExpired: false,
      reason: null,
      timestamp: new Date().toISOString(),
    })

    const { container } = render(<SessionExpiredBanner />)

    expect(container).toBeEmptyDOMElement()
  })
})
