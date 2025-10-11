import { useAuth as useOidcAuth } from 'react-oidc-context'
import type { User } from 'oidc-client-ts'

/**
 * Type-safe wrapper around react-oidc-context useAuth hook
 *
 * Provides authentication state and methods with TypeScript types.
 * See research.md for authentication flow details.
 */
export interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  user: User | null | undefined
  error: Error | null
  signinRedirect: () => Promise<void>
  signoutRedirect: () => Promise<void>
  removeUser: () => Promise<void>
}

export function useAuth(): AuthState {
  const auth = useOidcAuth()

  return {
    isAuthenticated: auth.isAuthenticated,
    isLoading: auth.isLoading,
    user: auth.user,
    error: auth.error || null,
    signinRedirect: () => auth.signinRedirect(),
    signoutRedirect: () => auth.signoutRedirect(),
    removeUser: () => auth.removeUser(),
  }
}
