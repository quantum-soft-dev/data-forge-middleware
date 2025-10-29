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
  hasRole: (role: string) => boolean
}

/**
 * Extract roles from Keycloak token.
 * Keycloak stores roles in token.profile.realm_access.roles
 */
function getUserRoles(user: User | null | undefined): string[] {
  if (!user?.profile) return []

  // Keycloak realm roles are in profile.realm_access.roles
  const realmAccess = user.profile.realm_access as { roles?: string[] } | undefined
  return realmAccess?.roles || []
}

export function useAuth(): AuthState {
  const auth = useOidcAuth()

  const hasRole = (role: string): boolean => {
    if (!auth.isAuthenticated || !auth.user) return false
    const roles = getUserRoles(auth.user)
    return roles.includes(role)
  }

  return {
    isAuthenticated: auth.isAuthenticated,
    isLoading: auth.isLoading,
    user: auth.user,
    error: auth.error || null,
    signinRedirect: () => auth.signinRedirect(),
    signoutRedirect: () => auth.signoutRedirect(),
    removeUser: () => auth.removeUser(),
    hasRole,
  }
}
