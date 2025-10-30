/**
 * Provider composition for the application
 *
 * Order matters! Providers are composed from outside to inside:
 * 1. AuthProvider - must wrap everything to provide authentication context
 * 2. QueryProvider - depends on auth for authenticated API calls
 * 3. RouterProvider - innermost, renders the actual application
 *
 * Usage in App.tsx:
 * ```tsx
 * import { AuthProvider, QueryProvider, RouterProvider } from '@/app/providers'
 *
 * <AuthProvider>
 *   <QueryProvider>
 *     <RouterProvider />
 *   </QueryProvider>
 * </AuthProvider>
 * ```
 */

export { AuthProvider } from './AuthProvider'
export { QueryProvider } from './QueryProvider'
export { RouterProvider } from './RouterProvider'
