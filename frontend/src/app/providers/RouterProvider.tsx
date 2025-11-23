import { RouterProvider as TanStackRouterProvider } from '@tanstack/react-router'
import { useAuth } from '@/entities/user-session/api/useAuth'
import { router } from '@/app/router'

/**
 * TanStack Router provider
 *
 * Provides type-safe routing for the application.
 * Router configuration is defined in @/app/router.tsx
 *
 * Auth context is passed to router for route guards.
 *
 * See Constitution Principle IX for type-safety requirements
 */
export function RouterProvider() {
  const auth = useAuth()

  return <TanStackRouterProvider router={router} context={{ auth }} />
}
