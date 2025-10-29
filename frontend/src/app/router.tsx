import { createRouter, createRoute, createRootRoute, Outlet, redirect } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import type { AuthContextProps } from 'react-oidc-context'

// Lazy-loaded page components for code splitting
const LoginPage = lazy(() => import('@/pages/login/LoginPage'))
const CallbackPage = lazy(() => import('@/pages/login/CallbackPage'))
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'))
const CreateAccountPage = lazy(() => import('@/pages/accounts/create/CreateAccountPage'))
const AccountsListPage = lazy(() => import('@/pages/accounts/users/AccountsListPage'))
const AccountDetailsPage = lazy(() => import('@/pages/accounts/details/AccountDetailsPage'))

// Router context type
interface RouterContext {
  auth: AuthContextProps
}

// Root route
const rootRoute = createRootRoute({
  component: () => (
    <Suspense
      fallback={
        <div className="flex h-screen items-center justify-center">
          <div className="text-center">
            <div className="mb-4 inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-current border-r-transparent" />
            <p className="text-sm text-muted-foreground">Loading...</p>
          </div>
        </div>
      }
    >
      <Outlet />
    </Suspense>
  ),
})

// Route definitions
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: LoginPage,
})

const callbackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/callback',
  component: CallbackPage,
})

const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboard',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }
  },
  component: DashboardPage,
})

// Unified User Management route (replaces old /accounts)
// Only accessible for users with ADMIN role
const usersListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Check for ADMIN role (Keycloak uses ROLE_ prefix)
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (!roles.includes('ROLE_ADMIN')) {
      // Redirect non-admin users to dashboard
      throw redirect({ to: '/dashboard' })
    }
  },
  component: AccountsListPage,
})

// Redirect old /accounts route to /admin/users for backwards compatibility
const accountsRedirectRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/accounts',
  beforeLoad: () => {
    throw redirect({ to: '/admin/users' })
  },
})

const createAccountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/create',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Check for ADMIN role
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (!roles.includes('ADMIN')) {
      throw redirect({ to: '/dashboard' })
    }
  },
  component: CreateAccountPage,
})

const accountDetailsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/$id',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Check for ADMIN role
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (!roles.includes('ADMIN')) {
      throw redirect({ to: '/dashboard' })
    }
  },
  component: AccountDetailsPage,
})

// Create route tree
const routeTree = rootRoute.addChildren([
  indexRoute,
  callbackRoute,
  dashboardRoute,
  accountsRedirectRoute,
  usersListRoute,
  createAccountRoute,
  accountDetailsRoute,
])

// Create router instance
export const router = createRouter({
  routeTree,
  defaultPreload: 'intent',
})

// Register router type for type-safe navigation
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
  interface RouteContext {
    auth: AuthContextProps
  }
}
