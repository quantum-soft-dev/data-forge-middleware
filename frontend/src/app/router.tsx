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
const UserSitesPage = lazy(() => import('@/pages/admin/user-sites/UserSitesPage'))
const SiteManagementPage = lazy(() => import('@/pages/site-management').then(m => ({ default: m.SiteManagementPage })))
const UploadHistoryPage = lazy(() => import('@/pages/upload-history/UploadHistoryPage'))
const BatchDetailPage = lazy(() => import('@/pages/upload-history/BatchDetailPage'))
const ComparisonPage = lazy(() => import('@/pages/comparison/ComparisonPage').then(m => ({ default: m.ComparisonPage })))
const ComparisonListPage = lazy(() => import('@/pages/comparison/ComparisonListPage').then(m => ({ default: m.ComparisonListPage })))
const ComparisonDetailPage = lazy(() => import('@/pages/comparison/ComparisonDetailPage'))

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

    // Check for ADMIN role (Keycloak uses ROLE_ prefix)
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (!roles.includes('ROLE_ADMIN')) {
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

    // Check for ADMIN role (Keycloak uses ROLE_ prefix)
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (!roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/dashboard' })
    }
  },
  component: AccountDetailsPage,
})

// Admin User Sites route (admin manages user's sites)
const userSitesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/$id/sites',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Check for ADMIN role (Keycloak uses ROLE_ prefix)
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (!roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/dashboard' })
    }
  },
  component: UserSitesPage,
})

// Site Management route (accessible to regular users only, not admins)
const siteManagementRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/sites',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Redirect admins to admin panel instead
    // Admins don't have user accounts, so they can't access user site management
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/admin/users' })
    }
  },
  component: SiteManagementPage,
})

// Upload History route (accessible to regular users only, not admins)
const uploadHistoryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/upload-history',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Redirect admins to admin panel instead
    // Admins don't have user accounts, so they can't access upload history
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/admin/users' })
    }
  },
  component: UploadHistoryPage,
})

// Batch Detail route (User Story 2 - Phase 4)
const batchDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/upload-history/$batchId',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Redirect admins to admin panel instead
    // Admins don't have user accounts, so they can't access batch details
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/admin/users' })
    }
  },
  component: BatchDetailPage,
})

// Comparison List route (Spec 009 - Phase 10: T131)
const comparisonListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Redirect admins to admin panel instead
    // Admins don't have user accounts, so they can't view comparisons
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/admin/users' })
    }
  },
  component: ComparisonListPage,
})

// Comparison Creation route (Spec 009 - User Story 1)
const comparisonCreateRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons/create',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Redirect admins to admin panel instead
    // Admins don't have user accounts, so they can't create comparisons
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/admin/users' })
    }
  },
  component: ComparisonPage,
})

// Comparison Detail route (Spec 009 - User Story 2)
const comparisonDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons/$comparisonId',
  beforeLoad: ({ context }) => {
    const { auth } = context as RouterContext
    if (!auth.isAuthenticated && !auth.isLoading) {
      throw redirect({ to: '/' })
    }

    // Redirect admins to admin panel instead
    // Admins don't have user accounts, so they can't view comparisons
    const realmAccess = auth.user?.profile?.realm_access as { roles?: string[] } | undefined
    const roles = realmAccess?.roles || []
    if (roles.includes('ROLE_ADMIN')) {
      throw redirect({ to: '/admin/users' })
    }
  },
  component: ComparisonDetailPage,
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
  userSitesRoute,
  siteManagementRoute,
  uploadHistoryRoute,
  batchDetailRoute,
  comparisonListRoute,
  comparisonCreateRoute,
  comparisonDetailRoute,
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
