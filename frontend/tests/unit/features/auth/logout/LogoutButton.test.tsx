import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LogoutButton } from '@/features/auth/logout/LogoutButton'
import * as useAuthModule from '@/entities/user-session/api/useAuth'

// Mock useAuth hook
vi.mock('@/entities/user-session/api/useAuth')

describe('LogoutButton', () => {
  it('renders button when authenticated', () => {
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: { profile: { sub: '123', email: 'test@example.com' } } as any,
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    })

    render(<LogoutButton />)
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument()
  })

  it('does not render when not authenticated', () => {
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      user: null,
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
      removeUser: vi.fn(),
    })

    const { container } = render(<LogoutButton />)
    expect(container.firstChild).toBeNull()
  })

  it('triggers logout on click', () => {
    const mockSignoutRedirect = vi.fn()
    vi.mocked(useAuthModule.useAuth).mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      user: { profile: { sub: '123', email: 'test@example.com' } } as any,
      error: null,
      signinRedirect: vi.fn(),
      signoutRedirect: mockSignoutRedirect,
      removeUser: vi.fn(),
    })

    render(<LogoutButton />)
    const button = screen.getByRole('button', { name: 'Sign Out' })
    fireEvent.click(button)

    expect(mockSignoutRedirect).toHaveBeenCalledTimes(1)
  })
})
