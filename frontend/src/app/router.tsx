import { createRouter, createRoute, createRootRoute, Outlet } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import { AuthenticationGuard } from '@/shared/lib/auth/AuthenticationGuard'

// Lazy-loaded page components for code splitting
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

// Route definitions - Auth0 automatically redirects to login, no index route needed
const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: () => <AuthenticationGuard component={DashboardPage} />,
})

// Admin routes - protected with AuthenticationGuard and RoleGuard for ROLE_ADMIN
const usersListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users',
  component: () => <AuthenticationGuard component={AccountsListPage} />,
})

const createAccountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/create',
  component: () => <AuthenticationGuard component={CreateAccountPage} />,
})

const accountDetailsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/$id',
  component: () => <AuthenticationGuard component={AccountDetailsPage} />,
})

const userSitesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/users/$id/sites',
  component: () => <AuthenticationGuard component={UserSitesPage} />,
})

// User routes - protected with AuthenticationGuard
const siteManagementRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/sites',
  component: () => <AuthenticationGuard component={SiteManagementPage} />,
})

const uploadHistoryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/upload-history',
  component: () => <AuthenticationGuard component={UploadHistoryPage} />,
})

const batchDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/upload-history/$batchId',
  component: () => <AuthenticationGuard component={BatchDetailPage} />,
})

const comparisonListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons',
  component: () => <AuthenticationGuard component={ComparisonListPage} />,
})

const comparisonCreateRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons/create',
  component: () => <AuthenticationGuard component={ComparisonPage} />,
})

const comparisonDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons/$comparisonId',
  component: () => <AuthenticationGuard component={ComparisonDetailPage} />,
})

// Create route tree
const routeTree = rootRoute.addChildren([
  dashboardRoute,
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
}
