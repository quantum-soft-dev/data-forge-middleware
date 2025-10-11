import { apiClient } from './client'
import type { InternalAxiosRequestConfig } from 'axios'

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

export function setupInterceptors(tokenGetter: () => string | undefined) {
  getAccessToken = tokenGetter

  // Request interceptor: attach JWT token
  apiClient.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
      const token = getAccessToken?.()

      if (token && config.headers) {
        config.headers.Authorization = `Bearer ${token}`
      }

      return config
    },
    (error) => {
      return Promise.reject(error)
    }
  )
}

// Response interceptor for handling errors globally (implemented in error-handler.ts)
