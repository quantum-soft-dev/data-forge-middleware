import { createRouter, createRoute, createRootRoute, Outlet } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import { AuthenticationGuard } from '@/shared/lib/auth/AuthenticationGuard'
import { UserOnlyGuard } from '@/shared/lib/auth/UserOnlyGuard'

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
const PluginsAdminPage = lazy(() => import('@/pages/admin/plugins/PluginsAdminPage'))
const MyPluginsPage = lazy(() => import('@/pages/account/plugins').then(m => ({ default: m.MyPluginsPage })))

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
  path: '/dashboard',
  component: () => <AuthenticationGuard component={DashboardPage} />,
})

// Index route redirects to dashboard
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: () => {
    window.location.href = '/dashboard'
    return null
  },
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

const pluginsAdminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin/plugins',
  component: () => <AuthenticationGuard component={PluginsAdminPage} />,
})

// User routes - protected with UserOnlyGuard (redirects admins to /admin/users)
const siteManagementRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/sites',
  component: () => <UserOnlyGuard component={SiteManagementPage} />,
})

const uploadHistoryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/upload-history',
  component: () => <UserOnlyGuard component={UploadHistoryPage} />,
})

const batchDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/upload-history/$batchId',
  component: () => <UserOnlyGuard component={BatchDetailPage} />,
})

const comparisonListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons',
  component: () => <UserOnlyGuard component={ComparisonListPage} />,
})

const comparisonCreateRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons/create',
  component: () => <UserOnlyGuard component={ComparisonPage} />,
})

const comparisonDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/comparisons/$comparisonId',
  component: () => <UserOnlyGuard component={ComparisonDetailPage} />,
})

const myPluginsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account/plugins',
  component: () => <UserOnlyGuard component={MyPluginsPage} />,
})

// Create route tree
const routeTree = rootRoute.addChildren([
  indexRoute,
  dashboardRoute,
  usersListRoute,
  createAccountRoute,
  accountDetailsRoute,
  userSitesRoute,
  pluginsAdminRoute,
  siteManagementRoute,
  uploadHistoryRoute,
  batchDetailRoute,
  comparisonListRoute,
  comparisonCreateRoute,
  comparisonDetailRoute,
  myPluginsRoute,
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
