import { createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { lazy } from 'react'

// Lazy-loaded page components for code splitting
const LoginPage = lazy(() => import('@/pages/login/LoginPage'))
const CallbackPage = lazy(() => import('@/pages/login/CallbackPage'))
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'))
const SubscriberListPage = lazy(() => import('@/pages/subscribers/list/SubscriberListPage'))

// Root route
const rootRoute = createRootRoute({
  component: () => (
    <div className="min-h-screen bg-background">
      {/* Outlet will render child routes */}
      <div id="root-outlet" />
    </div>
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
