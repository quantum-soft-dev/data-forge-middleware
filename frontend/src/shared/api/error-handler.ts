import { apiClient } from './client'
import { toast } from 'sonner'
import type { AxiosError } from 'axios'

/**
 * Global error handler for API requests
 *
 * Handles common HTTP error responses:
 * - 401 Unauthorized: Handled by token refresh interceptor (see interceptors.ts)
 * - 403 Forbidden: Show permission error
 * - 404 Not Found: Show not found error
 * - 409 Conflict: Show conflict error (e.g., duplicate email)
 * - 500 Server Error: Show generic server error
 * - Network Error: Show network error
 *
 * Note: 401 errors are NOT shown as toasts here because the response interceptor
 * handles them with automatic token refresh. If refresh fails, logout is triggered.
 *
 * Usage: Call setupErrorHandler() in App.tsx
 */

export function setupErrorHandler() {
  apiClient.interceptors.response.use(
    (response) => response,
    (error: AxiosError) => {
      // Network error (no response from server)
      if (!error.response) {
        toast.error('Network error. Please check your connection and try again.')
        return Promise.reject(error)
      }

      const { status, data } = error.response

      switch (status) {
        // Note: 401 is handled by setupResponseInterceptor() in interceptors.ts
        // which attempts token refresh before showing any error

        case 403:
          // Forbidden - wrong token type or insufficient permissions
          toast.error('You do not have permission to perform this action.')
          break

        case 404:
          // Not found
          toast.error(
            (data as { message?: string })?.message || 'Resource not found.'
          )
          break

        case 409:
          // Conflict (e.g., duplicate email)
          toast.error(
            (data as { message?: string })?.message ||
              'A conflict occurred. Please check your input.'
          )
          break

        case 413:
          // Payload too large
          toast.error('File too large. Maximum size is 500MB.')
          break

        case 500:
        default:
          // Server error or unknown error
          toast.error(
            (data as { message?: string })?.message ||
              'An unexpected error occurred. Please try again later.'
          )
          break
      }

      return Promise.reject(error)
    }
  )
}
