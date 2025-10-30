import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Header } from '@/widgets/header/Header'

// Mock the useAuth hook
vi.mock('@/entities/user-session/api/useAuth', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    isLoading: false,
    user: { profile: { name: 'Test User' } },
    error: null,
    signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(),
    removeUser: vi.fn(),
    hasRole: vi.fn((role: string) => role === 'ROLE_ADMIN'),
  }),
}))

// Mock LogoutButton
vi.mock('@/features/auth/logout/LogoutButton', () => ({
  LogoutButton: () => <button data-testid="logout-button">Sign Out</button>,
}))

// Mock TanStack Router
vi.mock('@tanstack/react-router', () => ({
  Link: ({ to, children }: { to: string; children: React.ReactNode }) => (
    <a href={to} data-testid={`link-${to}`}>
      {children}
    </a>
  ),
}))

describe('Header', () => {
  it('should render the header', () => {
    render(<Header />)
    const header = screen.getByRole('banner')
    expect(header).toBeInTheDocument()
  })

  it('should render navigation links', () => {
    render(<Header />)
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('User Management')).toBeInTheDocument()
  })

  it('should render Dashboard link with correct route', () => {
    render(<Header />)
    const dashboardLink = screen.getByTestId('link-/dashboard')
    expect(dashboardLink).toBeInTheDocument()
    expect(dashboardLink).toHaveTextContent('Dashboard')
  })

  it('should render User Management link with correct route for admin', () => {
    render(<Header />)
    const userManagementLink = screen.getByTestId('link-/admin/users')
    expect(userManagementLink).toBeInTheDocument()
    expect(userManagementLink).toHaveTextContent('User Management')
  })

  it('should render logout button', () => {
    render(<Header />)
    expect(screen.getByTestId('logout-button')).toBeInTheDocument()
    expect(screen.getByText('Sign Out')).toBeInTheDocument()
  })

  it('should render app title', () => {
    render(<Header />)
    expect(screen.getByText('DataForge Middleware')).toBeInTheDocument()
  })
})
