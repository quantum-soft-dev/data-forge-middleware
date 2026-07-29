import { apiClient } from './client'
import { toast } from 'sonner'
import type { AxiosError } from 'axios'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * Skip the global error toast for this request — for callers that render
     * their own error taxonomy (e.g. openPresignedDownload), so one failure
     * never produces two toasts.
     */
    suppressErrorToast?: boolean
  }
}

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

/**
 * The backend error body's message field (ErrorResponseDto.message), if any.
 * Single home for the error-body contract — the interceptor below and callers
 * that render their own error taxonomy (openPresignedDownload) both use it,
 * so a DTO shape change cannot silently degrade one of them.
 */
export function getServerErrorMessage(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('response' in error)) {
    return undefined
  }
  const data = (error as AxiosError).response?.data
  const message = (data as { message?: string } | undefined)?.message
  return typeof message === 'string' && message.length > 0 ? message : undefined
}

/**
 * HTTP status of a failed request, if the failure carries a server response.
 * Undefined for network errors and for anything that is not an Axios failure —
 * so callers can branch on a status without widening the error to `any`.
 */
export function getServerErrorStatus(error: unknown): number | undefined {
  if (typeof error !== 'object' || error === null || !('response' in error)) {
    return undefined
  }
  return (error as AxiosError).response?.status
}

export function setupErrorHandler() {
  apiClient.interceptors.response.use(
    (response) => response,
    (error: AxiosError) => {
      if (error.config?.suppressErrorToast) {
        return Promise.reject(error)
      }

      // Network error (no response from server)
      if (!error.response) {
        toast.error('Network error. Please check your connection and try again.')
        return Promise.reject(error)
      }

      const { status } = error.response

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
            getServerErrorMessage(error) ?? 'Resource not found.'
          )
          break

        case 409:
          // Conflict (e.g., duplicate email)
          toast.error(
            getServerErrorMessage(error) ??
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
            getServerErrorMessage(error) ??
              'An unexpected error occurred. Please try again later.'
          )
          break
      }

      return Promise.reject(error)
    }
  )
}
