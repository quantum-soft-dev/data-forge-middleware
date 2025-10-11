import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LoginButton } from '@/features/auth/login/LoginButton'
import * as useAuthModule from '@/entities/user-session/api/useAuth'

// Mock useAuth hook
vi.mock('@/entities/user-session/api/useAuth')

describe('LoginButton', () => {
  it('renders button with correct text', () => {
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    })

    render(<LoginButton />)
    expect(screen.getByText('Sign In with Keycloak')).toBeInTheDocument()
  })

  it('triggers login on click', () => {
    const mockSigninRedirect = vi.fn()
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
      error: null,
      signinRedirect: mockSigninRedirect,
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    })

    render(<LoginButton />)
    const button = screen.getByText('Sign In with Keycloak')
    fireEvent.click(button)

    expect(mockSigninRedirect).toHaveBeenCalledTimes(1)
  })

  it('shows loading state when isLoading is true', () => {
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
      user: null,
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    })

    render(<LoginButton />)
    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('disables button when loading', () => {
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
      user: null,
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    })

    render(<LoginButton />)
    const button = screen.getByRole('button')
    expect(button).toBeDisabled()
  })
})
