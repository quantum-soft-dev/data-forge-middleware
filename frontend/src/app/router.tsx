import { createRouter, createRoute, createRootRoute, Outlet, redirect } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import type { AuthContextProps } from 'react-oidc-context'

// Lazy-loaded page components for code splitting
const LoginPage = lazy(() => import('@/pages/login/LoginPage'))
const CallbackPage = lazy(() => import('@/pages/login/CallbackPage'))
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'))
const AccountListPage = lazy(() => import('@/pages/accounts/list/AccountListPage'))
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

const accountsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/accounts',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }
  },
  component: AccountListPage,
})

const createAccountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/accounts/create',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }
  },
  component: CreateAccountPage,
})

const usersListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }
  },
  component: AccountsListPage,
})

const accountDetailsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/$id',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }
  },
  component: AccountDetailsPage,
})

// Create route tree
const routeTree = rootRoute.addChildren([
  indexRoute,
  callbackRoute,
  dashboardRoute,
  accountsRoute,
  createAccountRoute,
  usersListRoute,
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
