import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ComponentType } from 'react'
import { UserOnlyGuard } from '@/shared/lib/auth/UserOnlyGuard'
import * as auth0React from '@auth0/auth0-react'

vi.mock('@auth0/auth0-react')

const useAuthMock = vi.fn()
vi.mock('@/entities/user-session/api/useAuth', () => ({
  useAuth: () => useAuthMock(),
}))

const loginWithRedirect = vi.fn().mockResolvedValue(undefined)

function mockAuth0State(state: { isAuthenticated: boolean; isLoading: boolean }) {
  vi.mocked(auth0React.useAuth0).mockReturnValue({
    ...state,
    loginWithRedirect,
  } as unknown as ReturnType<typeof auth0React.useAuth0>)
}

function Counter() {
  const [count, setCount] = useState(0)
  return <button onClick={() => setCount((value) => value + 1)}>count: {count}</button>
}

function Parent({ component }: { component: ComponentType }) {
  const [, forceRender] = useState(0)
  return (
    <>
      <button onClick={() => forceRender((value) => value + 1)}>re-render</button>
      <UserOnlyGuard component={component} />
    </>
  )
}

describe('UserOnlyGuard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(window, 'location', {
      value: { pathname: '/account/sites', href: '/account/sites' },
      writable: true,
    })
    mockAuth0State({ isAuthenticated: true, isLoading: false })
    useAuthMock.mockReturnValue({
      isLoading: false,
      isRolesLoading: false,
      hasRole: () => false,
    })
  })

  it('renders the protected component for a regular user', () => {
    render(<UserOnlyGuard component={() => <div>User Content</div>} />)

    expect(screen.getByText('User Content')).toBeInTheDocument()
  })

  it('shows the spinner and redirects admins away', () => {
    useAuthMock.mockReturnValue({
      isLoading: false,
      isRolesLoading: false,
      hasRole: (role: string) => role === 'ROLE_ADMIN',
    })

    render(<UserOnlyGuard component={() => <div>User Content</div>} />)

    expect(screen.queryByText('User Content')).not.toBeInTheDocument()
    expect(document.querySelector('.animate-spin')).toBeInTheDocument()
    expect(window.location.href).toBe('/admin/users')
  })

  it('redirects anonymous users to Auth0 and returns them to the current route', () => {
    mockAuth0State({ isAuthenticated: false, isLoading: false })

    render(<UserOnlyGuard component={() => <div>User Content</div>} />)

    expect(screen.queryByText('User Content')).not.toBeInTheDocument()
    expect(loginWithRedirect).toHaveBeenCalledWith({
      appState: { returnTo: '/account/sites' },
    })
  })

  it('keeps the protected component mounted across re-renders', async () => {
    // Same defect class as AuthenticationGuard: the guard used to build a
    // component type during render, remounting the subtree on every parent
    // render (issue #77, react-hooks/static-components).
    render(<Parent component={Counter} />)

    await userEvent.click(screen.getByRole('button', { name: /count: 0/ }))
    expect(screen.getByRole('button', { name: /count: 1/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 're-render' }))

    expect(screen.getByRole('button', { name: /count: 1/ })).toBeInTheDocument()
  })
})
