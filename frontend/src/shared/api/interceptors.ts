import { apiClient } from './client'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { toast } from 'sonner'
import { logger } from '../lib/logger'
import { refreshTokenWithLock, isTokenRefreshInitialized } from './token-refresh'
import { isAuth0Error, isTokenExpiredError } from './types'
import type { RetryableAxiosConfig } from './types'

/**
 * Axios request interceptor to attach JWT tokens (Auth0 migration)
 *
 * This interceptor must be initialized AFTER the Auth0Provider is mounted,
 * otherwise useAuth0() will not be available.
 *
 * JWT is attached to all API requests in the Authorization header.
 * Supports both sync and async token getters for Auth0 compatibility.
 *
 * Usage: Call setupInterceptors() in App.tsx after Auth0Provider mounts
 *
 * @version 3.0.0 (Token refresh on 401)
 */

// Store the auth getter function (supports async for Auth0)
let getAccessToken: (() => string | undefined | Promise<string | undefined>) | null = null
let requestInterceptorId: number | null = null
let responseInterceptorId: number | null = null

export function setupInterceptors(tokenGetter: () => string | undefined | Promise<string | undefined>) {
  getAccessToken = tokenGetter

  // Clear previous interceptor if it exists (prevent duplicates)
  if (requestInterceptorId !== null) {
    apiClient.interceptors.request.eject(requestInterceptorId)
    logger.info('[Interceptor]', '🔄 Cleared previous request interceptor')
  }

  // Request interceptor: attach JWT token (async for Auth0)
  requestInterceptorId = apiClient.interceptors.request.use(
    async (config: InternalAxiosRequestConfig) => {
      try {
        const token = await getAccessToken?.()

        if (token && config.headers) {
          config.headers.Authorization = `Bearer ${token}`
          logger.debug('[Interceptor]', '✅ Added token to request:', config.url)
          logger.debug('[Interceptor]', '🔑 Token preview:', token.substring(0, 50) + '...')
        } else {
          logger.warn('[Interceptor]', '⚠️ No token available for request:', config.url)
          logger.debug('[Interceptor]', '📊 Auth state:', {
            hasTokenGetter: !!getAccessToken,
            tokenValue: token ? 'present' : 'missing'
          })
        }
      } catch (error) {
        logger.error('[Interceptor]', '❌ Failed to get token:', error)
      }

      return config
    },
    (error) => {
      logger.error('[Interceptor]', '❌ Request error:', error)
      return Promise.reject(error)
    }
  )

  logger.info('[Interceptor]', '🚀 Request interceptor registered (Auth0)')
}

/**
 * Setup response interceptor for handling 401 errors with token refresh.
 *
 * Must be called after initTokenRefresh() to enable automatic token refresh.
 *
 * Flow:
 * 1. 401 received → attempt token refresh
 * 2. Refresh succeeds → retry original request with new token
 * 3. Refresh fails → logout user (handled by token-refresh.ts)
 */
export function setupResponseInterceptor(): void {
  // Clear previous interceptor if exists
  if (responseInterceptorId !== null) {
    apiClient.interceptors.response.eject(responseInterceptorId)
    logger.info('[Interceptor]', '🔄 Cleared previous response interceptor')
  }

  responseInterceptorId = apiClient.interceptors.response.use(
    // Success handler - pass through
    (response) => response,

    // Error handler - handle 401 with token refresh
    async (error: AxiosError) => {
      const config = error.config as RetryableAxiosConfig | undefined

      // Only handle 401 Unauthorized
      if (error.response?.status !== 401) {
        return Promise.reject(error)
      }

      // Prevent infinite retry loops
      if (config?._retry) {
        logger.warn('[Interceptor]', '⚠️ 401 after retry, not retrying again')
        return Promise.reject(error)
      }

      // Check if token refresh is initialized
      if (!isTokenRefreshInitialized()) {
        logger.warn('[Interceptor]', '⚠️ Token refresh not initialized, cannot refresh')
        return Promise.reject(error)
      }

      logger.info('[Interceptor]', '🔄 401 received, attempting token refresh...')

      try {
        // Attempt to refresh token
        const newToken = await refreshTokenWithLock()

        // Mark as retried to prevent infinite loops
        if (config) {
          config._retry = true
          config.headers.Authorization = `Bearer ${newToken}`
        }

        logger.info('[Interceptor]', '✅ Token refreshed, retrying request:', config?.url)

        // Retry the original request
        return apiClient.request(config!)
      } catch (refreshError) {
        logger.error('[Interceptor]', '❌ Token refresh failed:', refreshError)

        // T042: Handle network error during refresh
        if (isNetworkError(refreshError)) {
          toast.error('Network error. Please check your connection.')
          return Promise.reject(refreshError)
        }

        // T043: Handle Auth0 service unavailable
        if (isAuth0ServiceUnavailable(refreshError)) {
          toast.error('Authentication service unavailable. Please try again later.')
          return Promise.reject(refreshError)
        }

        // For token expiry errors, logout is handled by refreshTokenWithLock
        // No toast needed as user will be redirected to login
        if (isAuth0Error(refreshError) && isTokenExpiredError(refreshError.error)) {
          return Promise.reject(refreshError)
        }

        // For other unexpected errors, show a generic toast
        toast.error('Failed to refresh session. Please try again.')
        return Promise.reject(refreshError)
      }
    }
  )

  logger.info('[Interceptor]', '🚀 Response interceptor registered (401 handling)')
}

/**
 * Clear response interceptor.
 * Used for testing cleanup.
 */
export function clearResponseInterceptor(): void {
  if (responseInterceptorId !== null) {
    apiClient.interceptors.response.eject(responseInterceptorId)
    responseInterceptorId = null
  }
}

/**
 * Check if error is a network error (no response from server).
 * T042: Network errors should show toast but not trigger logout.
 */
function isNetworkError(error: unknown): boolean {
  if (error instanceof Error) {
    // Axios network error messages
    const networkMessages = [
      'Network Error',
      'ECONNREFUSED',
      'ENOTFOUND',
      'ETIMEDOUT',
      'ERR_NETWORK',
    ]
    return networkMessages.some(msg => error.message.includes(msg))
  }
  return false
}

/**
 * Check if error indicates Auth0 service is unavailable.
 * T043: Auth0 unavailable should show specific toast.
 */
function isAuth0ServiceUnavailable(error: unknown): boolean {
  // Check for Auth0 API errors with 503 status
  if (error && typeof error === 'object') {
    const err = error as { status?: number; statusCode?: number; message?: string }
    // Auth0 SDK may use status or statusCode
    if (err.status === 503 || err.statusCode === 503) {
      return true
    }
    // Check for specific Auth0 unavailable messages
    if (err.message?.includes('service_unavailable') || err.message?.includes('temporarily unavailable')) {
      return true
    }
  }
  return false
}
