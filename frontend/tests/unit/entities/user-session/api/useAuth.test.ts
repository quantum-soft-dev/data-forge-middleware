import { describe, it, expect, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useAuth } from '@/entities/user-session/api/useAuth'
import * as oidcContext from 'react-oidc-context'

// Mock react-oidc-context
vi.mock('react-oidc-context', () => ({
  useAuth: vi.fn(),
}))

describe('useAuth', () => {
  it('returns auth state from react-oidc-context', () => {
    const mockOidcAuth = {
      isAuthenticated: true,
      isLoading: false,
      user: { profile: { sub: '123', email: 'test@example.com' } },
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    }

    vi.mocked(oidcContext.useAuth).mockReturnValue(mockOidcAuth as any)

    const { result } = renderHook(() => useAuth())

    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.isLoading).toBe(false)
    expect(result.current.user).toEqual(mockOidcAuth.user)
    expect(result.current.error).toBe(null)
  })

  it('exposes signin and signout methods', () => {
    const mockSigninRedirect = vi.fn()
    const mockSignoutRedirect = vi.fn()

    vi.mocked(oidcContext.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
      error: null,
      signinRedirect: mockSigninRedirect,
      signoutRedirect: mockSignoutRedirect,
      removeUser: vi.fn(),
    } as any)

    const { result } = renderHook(() => useAuth())

    result.current.signinRedirect()
    expect(mockSigninRedirect).toHaveBeenCalledTimes(1)

    result.current.signoutRedirect()
    expect(mockSignoutRedirect).toHaveBeenCalledTimes(1)
  })

  it('converts undefined error to null', () => {
    vi.mocked(oidcContext.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
      error: undefined,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    } as any)

    const { result } = renderHook(() => useAuth())

    expect(result.current.error).toBe(null)
  })
})
