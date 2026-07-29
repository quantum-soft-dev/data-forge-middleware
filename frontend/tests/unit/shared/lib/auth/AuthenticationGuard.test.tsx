import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ComponentType } from 'react'
import { AuthenticationGuard } from '@/shared/lib/auth/AuthenticationGuard'
import * as auth0React from '@auth0/auth0-react'

// Mock Auth0 React hook
vi.mock('@auth0/auth0-react')

// Test component to wrap with AuthenticationGuard
const TestComponent = () => <div>Protected Content</div>

const loginWithRedirect = vi.fn().mockResolvedValue(undefined)

function mockAuth0State(state: { isAuthenticated: boolean; isLoading: boolean }) {
  vi.mocked(auth0React.useAuth0).mockReturnValue({
    ...state,
    loginWithRedirect,
  } as unknown as ReturnType<typeof auth0React.useAuth0>)
}

describe('AuthenticationGuard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(window, 'location', {
      value: { pathname: '/dashboard' },
      writable: true,
    })
  })

  it('renders loading spinner when authentication is in progress', () => {
    mockAuth0State({ isAuthenticated: false, isLoading: true })

    render(<AuthenticationGuard component={TestComponent} />)

    // Check for loading spinner container
    const container = document.querySelector('.flex.items-center.justify-center.min-h-screen')
    expect(container).toBeInTheDocument()

    // Check for spinner element
    const spinner = document.querySelector('.animate-spin')
    expect(spinner).toBeInTheDocument()

    // Still loading — no redirect yet
    expect(loginWithRedirect).not.toHaveBeenCalled()
  })

  it('renders protected component when user is authenticated', () => {
    mockAuth0State({ isAuthenticated: true, isLoading: false })

    render(<AuthenticationGuard component={TestComponent} />)

    // Check that protected content is rendered
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
    expect(loginWithRedirect).not.toHaveBeenCalled()
  })

  it('redirects anonymous users to Auth0 and returns them to the current route', () => {
    mockAuth0State({ isAuthenticated: false, isLoading: false })

    render(<AuthenticationGuard component={TestComponent} />)

    expect(screen.queryByText('Protected Content')).not.toBeInTheDocument()
    expect(loginWithRedirect).toHaveBeenCalledWith({
      appState: { returnTo: '/dashboard' },
    })
  })

  it('keeps the protected component mounted across re-renders', async () => {
    // Regression guard (issue #77, react-hooks/static-components): the guard used
    // to build a component type during render, which remounted the protected
    // subtree — and wiped its state — on every parent render.
    mockAuth0State({ isAuthenticated: true, isLoading: false })

    function Counter() {
      const [count, setCount] = useState(0)
      return <button onClick={() => setCount((value) => value + 1)}>count: {count}</button>
    }

    function Parent({ component }: { component: ComponentType }) {
      const [, forceRender] = useState(0)
      return (
        <>
          <button onClick={() => forceRender((value) => value + 1)}>re-render</button>
          <AuthenticationGuard component={component} />
        </>
      )
    }

    render(<Parent component={Counter} />)

    await userEvent.click(screen.getByRole('button', { name: /count: 0/ }))
    expect(screen.getByRole('button', { name: /count: 1/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 're-render' }))

    expect(screen.getByRole('button', { name: /count: 1/ })).toBeInTheDocument()
  })
})
