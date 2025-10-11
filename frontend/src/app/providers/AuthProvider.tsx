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
    // Store tokens in sessionStorage (not localStorage per security constraint)
    userStore: typeof window !== 'undefined'
      ? new WebStorageStateStore({ store: window.sessionStorage })
      : undefined,
  }

  return <OidcAuthProvider {...oidcConfig}>{children}</OidcAuthProvider>
}
