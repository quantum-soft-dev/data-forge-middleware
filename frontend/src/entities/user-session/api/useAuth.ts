import { useAuth0 } from '@auth0/auth0-react'
import { useState, useEffect } from 'react'

/**
 * Type-safe wrapper around Auth0 useAuth0 hook
 *
 * Provides authentication state and methods with TypeScript types.
 * Migrated from Keycloak to Auth0.
 */
export interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  user: Auth0User | undefined
  error: Error | undefined
  signinRedirect: () => Promise<void>
  signoutRedirect: () => void
  removeUser: () => Promise<void>
  hasRole: (role: string) => boolean
}

/**
 * Auth0 user type with custom claims
 */
export interface Auth0User {
  sub?: string
  name?: string
  email?: string
  email_verified?: boolean
  picture?: string
  'https://api.dataforge.com/roles'?: string[]
  'https://api.dataforge.com/accountId'?: string
  'https://api.dataforge.com/email'?: string
}

/**
 * Decoded access token payload with custom claims
 */
interface AccessTokenPayload {
  'https://api.dataforge.com/roles'?: string[]
  'https://api.dataforge.com/accountId'?: string
  'https://api.dataforge.com/email'?: string
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

  // Store roles from access token (not ID token)
  const [roles, setRoles] = useState<string[]>([])

  // Extract roles from access token when authenticated
  useEffect(() => {
    if (isAuthenticated) {
      getAccessTokenSilently()
        .then((accessToken) => {
          const payload = decodeJwtPayload(accessToken)
          const tokenRoles = payload?.['https://api.dataforge.com/roles'] || []
          console.log('[useAuth] Extracted roles from access token:', tokenRoles)
          setRoles(tokenRoles)
        })
        .catch((err) => {
          console.error('[useAuth] Failed to get access token:', err)
          setRoles([])
        })
    } else {
      setRoles([])
    }
  }, [isAuthenticated, getAccessTokenSilently])

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
    user,
    error,
    signinRedirect,
    signoutRedirect,
    removeUser,
    hasRole,
  }
}
