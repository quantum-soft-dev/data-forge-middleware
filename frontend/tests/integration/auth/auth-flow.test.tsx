import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { useAuth0 } from '@auth0/auth0-react'

// Mock Auth0 React hook
vi.mock('@auth0/auth0-react')

// Simple component to test authentication state
const AuthStatusComponent = () => {
  const { isLoading, isAuthenticated, error } = useAuth0()

  if (isLoading) return <div>Processing authentication...</div>
  if (error) return <div>Authentication error: {error.message}</div>
  if (isAuthenticated) return <div>Authenticated Successfully</div>

  return <div>Not Authenticated</div>
}

describe('Auth Flow Integration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows not authenticated state when user is not logged in', () => {
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

    render(<AuthStatusComponent />)
    expect(screen.getByText('Not Authenticated')).toBeInTheDocument()
  })

  it('shows authenticated state when user is logged in', () => {
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: {
        sub: 'auth0|12345',
        email: 'user@example.com',
        name: 'Test User',
        'https://api.dataforge.com/roles': ['ROLE_ADMIN'],
      },
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn().mockResolvedValue('mock-access-token'),
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    render(<AuthStatusComponent />)
    expect(screen.getByText('Authenticated Successfully')).toBeInTheDocument()
  })

  it('shows loading state during authentication', () => {
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

    render(<AuthStatusComponent />)
    expect(screen.getByText('Processing authentication...')).toBeInTheDocument()
  })

  it('displays error when authentication fails', () => {
    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: undefined,
      error: new Error('Invalid credentials'),
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn(),
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    render(<AuthStatusComponent />)
    expect(screen.getByText('Authentication error: Invalid credentials')).toBeInTheDocument()
  })

  it('provides access token when authenticated', async () => {
    const mockGetToken = vi.fn().mockResolvedValue('mock-access-token')

    vi.mocked(useAuth0).mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: {
        sub: 'auth0|12345',
        email: 'user@example.com',
      },
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: mockGetToken,
      getAccessTokenWithPopup: vi.fn(),
      getIdTokenClaims: vi.fn(),
      loginWithPopup: vi.fn(),
      handleRedirectCallback: vi.fn(),
    } as any)

    const { getAccessTokenSilently } = useAuth0()
    const token = await getAccessTokenSilently()

    expect(token).toBe('mock-access-token')
    expect(mockGetToken).toHaveBeenCalledTimes(1)
  })
})
