import { apiClient } from './client'
import type { InternalAxiosRequestConfig } from 'axios'
import { logger } from '../lib/logger'

/**
 * Axios request interceptor to attach JWT tokens
 *
 * This interceptor must be initialized AFTER the AuthProvider is mounted,
 * otherwise useAuth() will not be available.
 *
 * JWT is attached to all API requests in the Authorization header.
 *
 * Usage: Call setupInterceptors() in App.tsx after AuthProvider mounts
 */

// Store the auth getter function
let getAccessToken: (() => string | undefined) | null = null
let requestInterceptorId: number | null = null

export function setupInterceptors(tokenGetter: () => string | undefined) {
  getAccessToken = tokenGetter

  // Clear previous interceptor if it exists (prevent duplicates)
  if (requestInterceptorId !== null) {
    apiClient.interceptors.request.eject(requestInterceptorId)
    logger.info('[Interceptor]', '🔄 Cleared previous interceptor')
  }

  // Request interceptor: attach JWT token
  requestInterceptorId = apiClient.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      const token = getAccessToken?.()

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

      return config
    },
    (error) => {
      logger.error('[Interceptor]', '❌ Request error:', error)
      return Promise.reject(error)
    }
  )

  logger.info('[Interceptor]', '🚀 Request interceptor registered')
}

// Response interceptor for handling errors globally (implemented in error-handler.ts)
