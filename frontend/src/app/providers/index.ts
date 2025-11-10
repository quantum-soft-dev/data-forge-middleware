/**
 * Provider composition for the application
 *
 * Order matters! Providers are composed from outside to inside:
 * 1. Auth0Provider - Auth0 authentication context
 * 2. QueryProvider - depends on auth for authenticated API calls
 * 3. RouterProvider - innermost, renders the actual application
 *
 * Usage in App.tsx:
 * ```tsx
 * import { Auth0Provider, QueryProvider, RouterProvider } from '@/app/providers'
 *
 * <Auth0Provider>
 *   <QueryProvider>
 *     <RouterProvider />
 *   </QueryProvider>
 * </Auth0Provider>
 * ```
 */

export { Auth0Provider } from './Auth0Provider'
export { QueryProvider } from './QueryProvider'
export { RouterProvider } from './RouterProvider'
