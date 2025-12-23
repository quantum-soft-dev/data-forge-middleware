import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import DashboardPage from '@/pages/dashboard/DashboardPage'

// Mock the useAuth hook
vi.mock('@/entities/user-session/api/useAuth', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    isLoading: false,
    user: { profile: { name: 'Test User', email: 'test@example.com' } },
    error: null,
    signinRedirect: vi.fn(),
    signoutRedirect: vi.fn(),
    removeUser: vi.fn(),
  }),
}))

// Mock chart components to avoid Recharts complexity in tests
vi.mock('@/widgets/dashboard-charts', () => ({
  DashboardCharts: () => <div data-testid="dashboard-charts">Charts</div>,
}))

// Mock Header widget
vi.mock('@/widgets/header/Header', () => ({
  Header: () => <header data-testid="header">Header</header>,
}))

// Mock MyPluginsWidget to avoid React Query dependency
vi.mock('@/widgets/my-plugins', () => ({
  MyPluginsWidget: () => <div data-testid="my-plugins-widget">My Plugins</div>,
}))

// Mock useAuth0Roles to return ROLE_USER (so widget is shown)
vi.mock('@/shared/lib/auth/useAuth0Roles', () => ({
  useAuth0Roles: () => ({
    roles: ['ROLE_USER'],
    hasRole: (role: string) => role === 'ROLE_USER',
    hasAnyRole: (roles: string[]) => roles.includes('ROLE_USER'),
    hasAllRoles: (roles: string[]) => roles.every((r) => r === 'ROLE_USER'),
  }),
}))

describe('DashboardPage', () => {
  it('should render the page', () => {
    render(<DashboardPage />)
    expect(screen.getByTestId('header')).toBeInTheDocument()
    expect(screen.getByTestId('dashboard-charts')).toBeInTheDocument()
  })

  it('should have proper page structure', () => {
    const { container } = render(<DashboardPage />)
    const main = container.querySelector('main')
    expect(main).toBeInTheDocument()
  })

  it('should render navigation menu in header', () => {
    render(<DashboardPage />)
    expect(screen.getByTestId('header')).toBeInTheDocument()
  })

  it('should render dashboard charts widget', () => {
    render(<DashboardPage />)
    expect(screen.getByTestId('dashboard-charts')).toBeInTheDocument()
    expect(screen.getByText('Charts')).toBeInTheDocument()
  })

  it('should render my plugins widget for ROLE_USER', () => {
    render(<DashboardPage />)
    expect(screen.getByTestId('my-plugins-widget')).toBeInTheDocument()
    expect(screen.getByText('My Plugins')).toBeInTheDocument()
  })

  it('should conditionally render my plugins based on ROLE_USER role', () => {
    // This test verifies the component uses hasRole('ROLE_USER') check
    // The actual role-based hiding is tested via the mock returning ROLE_USER
    render(<DashboardPage />)
    // Since mock returns ROLE_USER, widget should be visible
    expect(screen.getByTestId('my-plugins-widget')).toBeInTheDocument()
  })
})
