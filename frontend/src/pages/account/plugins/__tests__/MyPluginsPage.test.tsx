import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MyPluginsPage } from '../MyPluginsPage'

// Mock Header widget
vi.mock('@/widgets/header/Header', () => ({
  Header: () => <header data-testid="header">Header</header>,
}))

// Mock MyPluginsWidget to avoid React Query dependency
vi.mock('@/widgets/my-plugins', () => ({
  MyPluginsWidget: () => <div data-testid="my-plugins-widget">My Plugins Widget</div>,
}))

// Mock WidgetErrorBoundary
vi.mock('@/shared/ui/WidgetErrorBoundary', () => ({
  WidgetErrorBoundary: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

describe('MyPluginsPage', () => {
  it('should render page with header', () => {
    render(<MyPluginsPage />)
    expect(screen.getByTestId('header')).toBeInTheDocument()
  })

  it('should render page title "My Plugins"', () => {
    render(<MyPluginsPage />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('My Plugins')
  })

  it('should render page description', () => {
    render(<MyPluginsPage />)
    expect(screen.getByText('Manage your plugin integrations and activate new features')).toBeInTheDocument()
  })

  it('should render MyPluginsWidget', () => {
    render(<MyPluginsPage />)
    expect(screen.getByTestId('my-plugins-widget')).toBeInTheDocument()
  })

  it('should have proper page structure', () => {
    const { container } = render(<MyPluginsPage />)
    const main = container.querySelector('main')
    expect(main).toBeInTheDocument()
  })
})
