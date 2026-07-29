import { useAuth0 } from '@auth0/auth0-react'
import { useState, useEffect } from 'react'
import { env } from '@/shared/config/env'

// Get claims namespace from environment
const CLAIMS_NAMESPACE = env.auth0.claimsNamespace

/**
 * Type-safe wrapper around Auth0 useAuth0 hook
 *
 * Provides authentication state and methods with TypeScript types.
 * Migrated from Keycloak to Auth0.
 */
export interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  isRolesLoading: boolean
  user: Auth0User | undefined
  error: Error | undefined
  signinRedirect: () => Promise<void>
  signoutRedirect: () => void
  removeUser: () => Promise<void>
  hasRole: (role: string) => boolean
}

/**
 * Auth0 user type with custom claims
 * Note: Claim keys are dynamic based on CLAIMS_NAMESPACE
 */
export interface Auth0User {
  sub?: string
  name?: string
  email?: string
  email_verified?: boolean
  picture?: string
  [key: string]: any // Allow dynamic claim keys
}

/**
 * Decoded access token payload with custom claims
 */
interface AccessTokenPayload {
  [key: string]: any
}

/**
 * Decode JWT token payload (base64url decode)
 */
function decodeJwtPayload(token: string): AccessTokenPayload | null {
  try {
    const base64Url = token.split('.')[1]
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    return JSON.parse(jsonPayload)
  } catch (error) {
    console.error('[useAuth] Failed to decode JWT token:', error)
    return null
  }
}

export function useAuth(): AuthState {
  const {
    isAuthenticated,
    isLoading,
    user,
    error,
    loginWithRedirect,
    logout,
    getAccessTokenSilently
  } = useAuth0<Auth0User>()

  // Roles come from the access token (not the ID token) and are fetched
  // asynchronously, so they are tracked together with their loading flag.
  const [rolesState, setRolesState] = useState<{ roles: string[]; isLoading: boolean }>(() => ({
    roles: [],
    isLoading: isAuthenticated,
  }))

  // Reset while rendering when the session flips: an effect would leave one
  // render showing the previous session's roles.
  const [sessionIsAuthenticated, setSessionIsAuthenticated] = useState(isAuthenticated)
  if (isAuthenticated !== sessionIsAuthenticated) {
    setSessionIsAuthenticated(isAuthenticated)
    setRolesState({ roles: [], isLoading: isAuthenticated })
  }

  // Extract roles from access token when authenticated
  useEffect(() => {
    if (!isAuthenticated) return

    let cancelled = false

    getAccessTokenSilently()
      .then((accessToken) => {
        const payload = decodeJwtPayload(accessToken)
        const rolesClaimKey = `${CLAIMS_NAMESPACE}/roles`
        const tokenRoles = payload?.[rolesClaimKey] || []
        console.log('[useAuth] Extracted roles from access token:', tokenRoles, 'using claim:', rolesClaimKey)
        if (!cancelled) setRolesState({ roles: tokenRoles, isLoading: false })
      })
      .catch((err) => {
        console.error('[useAuth] Failed to get access token:', err)
        if (!cancelled) setRolesState({ roles: [], isLoading: false })
      })

    // Ignore a response that arrives after the session changed
    return () => {
      cancelled = true
    }
  }, [isAuthenticated, getAccessTokenSilently])

  const roles = rolesState.roles
  const isRolesLoading = rolesState.isLoading

  const hasRole = (role: string): boolean => {
    if (!isAuthenticated) return false
    return roles.includes(role) || roles.includes(`ROLE_${role}`)
  }

  const signinRedirect = async () => {
    await loginWithRedirect({
      appState: {
        returnTo: window.location.pathname
      }
    })
  }

  const signoutRedirect = () => {
    logout({
      logoutParams: {
        returnTo: window.location.origin
      }
    })
  }

  const removeUser = async () => {
    // Auth0 doesn't have removeUser - just clear tokens by logging out
    // This is used for silent logout without redirect
    await getAccessTokenSilently({ cacheMode: 'off' })
  }

  return {
    isAuthenticated,
    isLoading,
    isRolesLoading,
    user,
    error,
    signinRedirect,
    signoutRedirect,
    removeUser,
    hasRole,
  }
}
