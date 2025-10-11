import { createRouter, createRoute, createRootRoute, Outlet } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'

// Lazy-loaded page components for code splitting
const LoginPage = lazy(() => import('@/pages/login/LoginPage'))
const CallbackPage = lazy(() => import('@/pages/login/CallbackPage'))
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'))
const SubscriberListPage = lazy(() => import('@/pages/subscribers/list/SubscriberListPage'))

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
  component: DashboardPage,
})

const subscribersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/subscribers',
  component: SubscriberListPage,
})

// Create route tree
const routeTree = rootRoute.addChildren([
  indexRoute,
  callbackRoute,
  dashboardRoute,
  subscribersRoute,
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
