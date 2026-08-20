import type { InternalAxiosRequestConfig } from 'axios'

/**
 * Token refresh and session management types.
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see specs/012-key-caching-logic/data-model.md
 */

/** Options for getAccessTokenSilently */
export interface GetAccessTokenOptions {
  /** Cache mode: 'on' (default), 'off' (force refresh), 'cache-only' */
  cacheMode?: 'on' | 'off' | 'cache-only'
}

/** Callback type for getting access token from Auth0 SDK */
export type GetAccessTokenFn = (options?: GetAccessTokenOptions) => Promise<string>

/** Callback type for logout from Auth0 SDK */
export type LogoutFn = () => void

/**
 * Auth0 error codes that indicate refresh token has expired.
 * These errors trigger automatic logout.
 */
export type Auth0ExpiredErrorCode =
  | 'missing_refresh_token'
  | 'invalid_grant'
  | 'login_required'

/**
 * Reasons for session expiry.
 * Used for analytics and displaying appropriate messages.
 */
export type SessionExpiryReason =
  | 'refresh_token_expired'
  | 'manual_logout'

/**
 * Session expiry data stored in sessionStorage.
 * Persists across redirects but cleared on tab close.
 */
export interface SessionExpiryData {
  /** Whether session was expired */
  isExpired: boolean
  /** Reason for expiry */
  reason: SessionExpiryReason | null
  /** ISO timestamp of when expiry occurred */
  timestamp: string
}

/**
 * Extended Axios request config with retry tracking.
 * Prevents infinite retry loops on 401 errors.
 */
export interface RetryableAxiosConfig extends InternalAxiosRequestConfig {
  /** Flag indicating this request has already been retried after 401 */
  _retry?: boolean
}

/**
 * Mark a rejection as already considered by a toast interceptor.
 *
 * Axios feeds a rejected promise from one response interceptor into the next,
 * and a successful refresh retries via `apiClient.request`, which re-enters
 * the whole chain. Without a mark, the same failure toasts twice — once on
 * the inner request and once on the outer rejection (issue #239 route 4).
 * The 401 interceptor also marks a failed refresh so the global handler does
 * not fill its silence (expired-token logout) or stack a network toast on a
 * named refresh failure (routes 2–3).
 */
export function markErrorToastHandled(error: unknown): void {
  if (typeof error === 'object' && error !== null) {
    const handled = error as { errorToastHandled?: boolean }
    handled.errorToastHandled = true
  }
}

/** Whether {@link markErrorToastHandled} has already run for this rejection. */
export function isErrorToastHandled(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { errorToastHandled?: boolean }).errorToastHandled === true
  )
}

/**
 * Auth0 error object structure.
 * Thrown by getAccessTokenSilently when refresh fails.
 */
export interface Auth0Error extends Error {
  error: string
  error_description?: string
}

/**
 * Check if an error is an Auth0 error with specific error code.
 */
export function isAuth0Error(error: unknown): error is Auth0Error {
  return (
    error !== null &&
    typeof error === 'object' &&
    'error' in error &&
    typeof (error as Auth0Error).error === 'string'
  )
}

/**
 * Check if error code indicates refresh token expiry.
 */
export function isTokenExpiredError(errorCode: string): boolean {
  const expiredCodes: Auth0ExpiredErrorCode[] = [
    'missing_refresh_token',
    'invalid_grant',
    'login_required',
  ]
  return expiredCodes.includes(errorCode as Auth0ExpiredErrorCode)
}
