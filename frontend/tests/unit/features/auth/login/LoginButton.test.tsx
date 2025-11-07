import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LoginButton } from '@/features/auth/login/LoginButton'
import { useAuth0 } from '@auth0/auth0-react'

// Mock Auth0 React hook
vi.mock('@auth0/auth0-react')

describe('LoginButton', () => {
  it('renders button with correct text', () => {
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

    render(<LoginButton />)
    expect(screen.getByText('Sign In with Auth0')).toBeInTheDocument()
  })

  it('triggers login on click', () => {
    const mockLoginWithRedirect = vi.fn()
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: undefined,
      error: undefined,
      loginWithRedirect: mockLoginWithRedirect,
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn(),
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    render(<LoginButton />)
    const button = screen.getByText('Sign In with Auth0')
    fireEvent.click(button)

    expect(mockLoginWithRedirect).toHaveBeenCalledTimes(1)
    expect(mockLoginWithRedirect).toHaveBeenCalledWith({
      appState: {
        returnTo: '/dashboard',
      },
    })
  })

  it('shows loading state when isLoading is true', () => {
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
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

    render(<LoginButton />)
    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('disables button when loading', () => {
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
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

    render(<LoginButton />)
    const button = screen.getByRole('button')
    expect(button).toBeDisabled()
  })
})
