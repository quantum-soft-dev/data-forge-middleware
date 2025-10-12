import { AuthProvider as OidcAuthProvider } from 'react-oidc-context'
import { WebStorageStateStore } from 'oidc-client-ts'
import { env } from '@/shared/config/env'
import type { ReactNode } from 'react'

interface AuthProviderProps {
  children: ReactNode
}

/**
 * Authentication provider using react-oidc-context for Keycloak integration
 *
 * Configuration:
 * - Authority: Keycloak realm URL
 * - Client ID: Public client configured in Keycloak
 * - Redirect URI: OAuth callback URL
 * - Silent Renew: Automatic token refresh enabled
 * - PKCE: Enabled by default (security best practice)
 * - Storage: sessionStorage (see UX trade-off below)
 *
 * UX Trade-off: sessionStorage vs localStorage
 * ============================================
 * Current: sessionStorage
 * - PRO: Tokens cleared when tab/window closes (enhanced security)
 * - PRO: Reduces risk of token theft from persistent storage
 * - CON: Users must re-login when closing/reopening tab
 * - CON: Sessions don't persist across browser restarts
 *
 * Alternative: localStorage
 * - PRO: Sessions persist across tab closes and browser restarts
 * - PRO: Better UX - users stay logged in longer
 * - CON: Tokens persist in browser indefinitely (higher security risk)
 * - CON: Requires manual token cleanup on logout
 *
 * Decision Rationale:
 * We chose sessionStorage for this admin UI to prioritize security over convenience.
 * Admin users access sensitive data and operations, so forcing re-authentication
 * after tab close is an acceptable trade-off. For end-user facing apps, consider
 * localStorage with proper token expiration and refresh logic.
 *
 * To switch to localStorage: change window.sessionStorage to window.localStorage
 *
 * See research.md for detailed authentication flow diagram
 */
export function AuthProvider({ children }: AuthProviderProps) {
  const oidcConfig = {
    authority: `${env.keycloak.url}/realms/${env.keycloak.realm}`,
    client_id: env.keycloak.clientId,
    redirect_uri: window.location.origin + '/callback',
    post_logout_redirect_uri: window.location.origin,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
    loadUserInfo: true,
    // Store tokens in sessionStorage (see UX trade-off documentation above)
    // Security over convenience: tokens cleared when tab closes
    userStore: typeof window !== 'undefined'
      ? new WebStorageStateStore({ store: window.sessionStorage })
      : undefined,
  }

  return <OidcAuthProvider {...oidcConfig}>{children}</OidcAuthProvider>
}
