import { RouterProvider as TanStackRouterProvider } from '@tanstack/react-router'
import { router } from '@/app/router'

/**
 * TanStack Router provider
 *
 * Provides type-safe routing for the application.
 * Router configuration is defined in @/app/router.tsx
 *
 * See Constitution Principle IX for type-safety requirements
 */
export function RouterProvider() {
  return <TanStackRouterProvider router={router} />
}
