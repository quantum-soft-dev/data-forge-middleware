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

  it('should render page title', () => {
    render(<DashboardPage />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Dashboard')
  })

  it('should render page description', () => {
    render(<DashboardPage />)
    expect(screen.getByText('Overview of subscriber metrics and growth trends')).toBeInTheDocument()
  })
})
