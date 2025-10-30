/**
 * User Session entity types
 *
 * Represents an authenticated user's session with Keycloak OIDC tokens.
 * See data-model.md for complete entity definition.
 */

/**
 * User information from Keycloak UserInfo endpoint
 */
export interface UserInfo {
  sub: string // Subject (user ID in Keycloak)
  email: string
  email_verified: boolean
  name?: string
  given_name?: string
  family_name?: string
  preferred_username?: string
  realm_roles?: string[]
}

/**
 * User session state
 *
 * Contains OIDC tokens and user information.
 * Managed by react-oidc-context AuthProvider.
 */
export interface UserSession {
  accessToken: string
  refreshToken?: string
  idToken?: string
  expiresAt: number // Unix timestamp
  user: UserInfo
  isAuthenticated: boolean
}

/**
 * Authentication error types
 */
export type AuthError =
  | 'login_failed'
  | 'token_expired'
  | 'token_refresh_failed'
  | 'keycloak_unavailable'
  | 'invalid_token'
  | 'network_error'

/**
 * Authentication error with context
 */
export interface AuthErrorDetails {
  type: AuthError
  message: string
  originalError?: Error
}
