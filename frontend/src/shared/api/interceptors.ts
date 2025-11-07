import { apiClient } from './client'
import type { InternalAxiosRequestConfig } from 'axios'
import { logger } from '../lib/logger'

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
 * @version 2.0.0 (Auth0 migration)
 */

// Store the auth getter function (supports async for Auth0)
let getAccessToken: (() => string | undefined | Promise<string | undefined>) | null = null
let requestInterceptorId: number | null = null

export function setupInterceptors(tokenGetter: () => string | undefined | Promise<string | undefined>) {
  getAccessToken = tokenGetter

  // Clear previous interceptor if it exists (prevent duplicates)
  if (requestInterceptorId !== null) {
    apiClient.interceptors.request.eject(requestInterceptorId)
    logger.info('[Interceptor]', '🔄 Cleared previous interceptor')
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

// Response interceptor for handling errors globally (implemented in error-handler.ts)
