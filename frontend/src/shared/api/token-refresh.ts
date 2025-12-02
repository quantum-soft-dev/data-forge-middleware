import type { GetAccessTokenFn, LogoutFn } from './types'
import { isAuth0Error, isTokenExpiredError } from './types'
import { setSessionExpired } from '../lib/auth/session-expiry'
import { logger } from '../lib/logger'

/**
 * Token refresh manager with lock mechanism.
 *
 * Handles automatic token refresh on 401 errors with:
 * - Promise-based lock to prevent concurrent refresh attempts
 * - Auth0 SDK integration via callbacks
 * - Graceful logout on refresh token expiry
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see specs/012-key-caching-logic/research.md
 */

/** Whether a token refresh is currently in progress */
let isRefreshing = false

/** Promise that resolves when current refresh completes */
let refreshPromise: Promise<string> | null = null

/** Callback to get fresh token from Auth0 SDK */
let getAccessToken: GetAccessTokenFn | null = null

/** Callback to logout user on refresh failure */
let onLogout: LogoutFn | null = null

/**
 * Initialize token refresh manager with Auth0 callbacks.
 *
 * Must be called after Auth0Provider is mounted.
 *
 * @param getAccessTokenSilently - Auth0 getAccessTokenSilently function
 * @param logout - Auth0 logout function
 */
export function initTokenRefresh(
  getAccessTokenSilently: GetAccessTokenFn,
  logout: LogoutFn
): void {
  getAccessToken = getAccessTokenSilently
  onLogout = logout
  logger.info('[TokenRefresh]', '✅ Token refresh manager initialized')
}

/**
 * Refresh access token with lock mechanism.
 *
 * - First caller triggers refresh
 * - Subsequent callers wait for existing refresh
 * - Returns new token on success
 * - Triggers logout on refresh token expiry
 *
 * @returns Promise resolving to new access token
 * @throws Error if refresh fails (non-expiry errors)
 */
export async function refreshTokenWithLock(): Promise<string> {
  // If refresh already in progress, wait for it
  if (isRefreshing && refreshPromise) {
    logger.debug('[TokenRefresh]', '⏳ Waiting for existing refresh...')
    return refreshPromise
  }

  // Check if initialized
  if (!getAccessToken) {
    throw new Error('Token refresh not initialized. Call initTokenRefresh first.')
  }

  // Start new refresh
  isRefreshing = true
  logger.debug('[TokenRefresh]', '🔄 Starting token refresh...')

  refreshPromise = (async () => {
    try {
      // Force refresh by bypassing cache
      const newToken = await getAccessToken!({ cacheMode: 'off' })
      logger.info('[TokenRefresh]', '✅ Token refreshed successfully')
      return newToken
    } catch (error) {
      logger.error('[TokenRefresh]', '❌ Token refresh failed:', error)

      // Check if this is an Auth0 token expiry error
      if (isAuth0Error(error) && isTokenExpiredError(error.error)) {
        logger.warn('[TokenRefresh]', '🔒 Refresh token expired, logging out...')

        // Set session expired flag before logout
        try {
          setSessionExpired('refresh_token_expired')
        } catch {
          // Ignore sessionStorage errors
        }

        // Trigger logout
        if (onLogout) {
          onLogout()
        }
      }

      throw error
    } finally {
      // Reset state
      isRefreshing = false
      refreshPromise = null
    }
  })()

  return refreshPromise
}

/**
 * Check if token refresh manager is initialized.
 */
export function isTokenRefreshInitialized(): boolean {
  return getAccessToken !== null && onLogout !== null
}

/**
 * Check if a refresh is currently in progress.
 */
export function isRefreshInProgress(): boolean {
  return isRefreshing
}

/**
 * Reset token refresh state.
 * Used for testing and cleanup.
 */
export function resetTokenRefreshState(): void {
  isRefreshing = false
  refreshPromise = null
  getAccessToken = null
  onLogout = null
}
