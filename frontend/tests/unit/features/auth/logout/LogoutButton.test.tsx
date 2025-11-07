import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LogoutButton } from '@/features/auth/logout/LogoutButton'
import { useAuth0 } from '@auth0/auth0-react'

// Mock Auth0 React hook
vi.mock('@auth0/auth0-react')

describe('LogoutButton', () => {
  it('renders button when authenticated', () => {
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: {
        sub: 'auth0|123',
        email: 'test@example.com',
        name: 'Test User',
      },
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn(),
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    render(<LogoutButton />)
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument()
  })

  it('does not render when not authenticated', () => {
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: undefined,
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn(),
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    const { container } = render(<LogoutButton />)
    expect(container.firstChild).toBeNull()
  })

  it('triggers logout on click', () => {
    const mockLogout = vi.fn()
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: {
        sub: 'auth0|123',
        email: 'test@example.com',
        name: 'Test User',
      },
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: mockLogout,
      getAccessTokenSilently: vi.fn(),
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    render(<LogoutButton />)
    const button = screen.getByRole('button', { name: 'Sign Out' })
    fireEvent.click(button)

    expect(mockLogout).toHaveBeenCalledTimes(1)
    expect(mockLogout).toHaveBeenCalledWith({
      logoutParams: {
        returnTo: window.location.origin,
      },
    })
  })
})
