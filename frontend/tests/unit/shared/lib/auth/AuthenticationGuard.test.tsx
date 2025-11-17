import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AuthenticationGuard } from '@/shared/lib/auth/AuthenticationGuard'
import * as auth0React from '@auth0/auth0-react'

// Mock Auth0 React hook
vi.mock('@auth0/auth0-react')

// Test component to wrap with AuthenticationGuard
const TestComponent = () => <div>Protected Content</div>

describe('AuthenticationGuard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders loading spinner when authentication is in progress', () => {
    // Mock withAuthenticationRequired to show loading state
    vi.mocked(auth0React.withAuthenticationRequired).mockImplementation(
      (component: any, options: any) => {
        // Call the onRedirecting callback to get the loading component
        const LoadingComponent = options?.onRedirecting
        return LoadingComponent ? LoadingComponent : component
      }
    )

    render(<AuthenticationGuard component={TestComponent} />)

    // Check for loading spinner container
    const container = document.querySelector('.flex.items-center.justify-center.min-h-screen')
    expect(container).toBeInTheDocument()

    // Check for spinner element
    const spinner = document.querySelector('.animate-spin')
    expect(spinner).toBeInTheDocument()
  })

  it('renders protected component when user is authenticated', () => {
    // Mock withAuthenticationRequired to return the original component
    vi.mocked(auth0React.withAuthenticationRequired).mockImplementation(
      (component: any) => component
    )

    render(<AuthenticationGuard component={TestComponent} />)

    // Check that protected content is rendered
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
  })

  it('passes returnTo prop to withAuthenticationRequired', () => {
    // Mock window.location.pathname
    Object.defineProperty(window, 'location', {
      value: { pathname: '/dashboard' },
      writable: true,
    })

    vi.mocked(auth0React.withAuthenticationRequired).mockImplementation(
      (component: any) => component
    )

    render(<AuthenticationGuard component={TestComponent} />)

    // Verify withAuthenticationRequired was called with correct options
    expect(auth0React.withAuthenticationRequired).toHaveBeenCalledWith(
      TestComponent,
      expect.objectContaining({
        returnTo: '/dashboard',
      })
    )
  })

  it('provides onRedirecting callback to show loading spinner', () => {
    vi.mocked(auth0React.withAuthenticationRequired).mockImplementation(
      (component: any) => component
    )

    render(<AuthenticationGuard component={TestComponent} />)

    // Verify withAuthenticationRequired was called with onRedirecting callback
    const call = vi.mocked(auth0React.withAuthenticationRequired).mock.calls[0]
    expect(call[1]).toHaveProperty('onRedirecting')
    expect(typeof call[1]?.onRedirecting).toBe('function')
  })
})
