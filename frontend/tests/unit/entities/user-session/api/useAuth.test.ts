import { describe, it, expect, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useAuth } from '@/entities/user-session/api/useAuth'
import * as auth0React from '@auth0/auth0-react'

// Mock @auth0/auth0-react
vi.mock('@auth0/auth0-react', () => ({
  useAuth0: vi.fn(),
}))

describe('useAuth', () => {
  it('returns auth state from useAuth0', async () => {
    const mockAuth0User = {
      sub: 'auth0|123',
      email: 'test@example.com',
      name: 'Test User',
      picture: 'https://example.com/avatar.png',
      updated_at: '2023-01-01T00:00:00.000Z',
    }

    const mockAuth0State = {
      isAuthenticated: true,
      isLoading: false,
      user: mockAuth0User,
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn().mockResolvedValue('header.eyJodHRwczovL2FwaS5kYXRhZm9yZ2UuY29tL3JvbGVzIjpbIlJPTEVfQURNSU4iXX0.signature'),
    }

    vi.mocked(auth0React.useAuth0).mockReturnValue(mockAuth0State as any)

    const { result } = renderHook(() => useAuth())

    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.isLoading).toBe(false)
    expect(result.current.user).toEqual(mockAuth0User)
    expect(result.current.error).toBeUndefined()

    // Wait for useEffect to complete
    await waitFor(() => {
      expect(mockAuth0State.getAccessTokenSilently).toHaveBeenCalled()
    })
  })

  it('exposes login and logout methods', () => {
    const mockLoginWithRedirect = vi.fn()
    const mockLogout = vi.fn()

    vi.mocked(auth0React.useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: undefined,
      error: undefined,
      loginWithRedirect: mockLoginWithRedirect,
      logout: mockLogout,
      getAccessTokenSilently: vi.fn().mockResolvedValue('header.eyJodHRwczovL2FwaS5kYXRhZm9yZ2UuY29tL3JvbGVzIjpbIlJPTEVfQURNSU4iXX0.signature'),
    } as any)

    const { result } = renderHook(() => useAuth())

    result.current.signinRedirect()
    expect(mockLoginWithRedirect).toHaveBeenCalledTimes(1)

    result.current.signoutRedirect()
    expect(mockLogout).toHaveBeenCalledTimes(1)
  })

  it('returns undefined error when no error exists', () => {
    vi.mocked(auth0React.useAuth0).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: undefined,
      error: undefined,
      loginWithRedirect: vi.fn(),
      logout: vi.fn(),
      getAccessTokenSilently: vi.fn().mockResolvedValue('header.eyJodHRwczovL2FwaS5kYXRhZm9yZ2UuY29tL3JvbGVzIjpbIlJPTEVfQURNSU4iXX0.signature'),
    } as any)

    const { result } = renderHook(() => useAuth())

    expect(result.current.error).toBeUndefined()
  })
})
