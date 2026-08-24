import { apiClient } from './client'
import { toast } from 'sonner'
import type { AxiosError } from 'axios'
import { isAuth0Error, isErrorToastHandled, markErrorToastHandled } from './types'

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
 * - 401: leftover 401s (refresh not initialized, or already retried) toast a
 *   session message. A refresh that was attempted is owned by interceptors.ts.
 * - 403 / 404 / 409 / 413 / 500: status toasts; 404 and 409 quote the server.
 * - No response: network toast, unless the error is Auth0-shaped (not a
 *   connection failure).
 *
 * A 401 is never a network error and never the generic unexpected-error default.
 * The handled mark stops a retried `apiClient.request` toasting twice.
 *
 * Usage: Call setupErrorHandler() in App.tsx. Calling it again replaces the
 * previous registration, so however often the caller's effect re-runs, repeated
 * registrations cannot multiply the toasts for one failure (issue #225).
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

/**
 * Id of the interceptor this module has registered, or null before the first
 * call. App.tsx sets the handler up from an effect keyed on the Auth0 state,
 * which re-runs as `isLoading` and `isAuthenticated` settle, so a second call
 * has to replace the first interceptor — axios keeps every registration it is
 * given, and each one would toast the same failure again. Same shape as
 * setupInterceptors/setupResponseInterceptor in interceptors.ts.
 */
let errorInterceptorId: number | null = null

/**
 * Eject this module's interceptor and forget its id.
 *
 * The counterpart of clearResponseInterceptor() in interceptors.ts, and there
 * for the same reason: ejecting by a remembered index is only safe while nobody
 * resets `apiClient.interceptors.response`. A teardown that calls `.clear()`
 * restarts the ids at 0, after which a remembered id points at whatever now
 * occupies that slot — so a test that clears the chain resets this module too,
 * rather than leaving it holding an index into an array it no longer knows.
 */
export function clearErrorHandler(): void {
  if (errorInterceptorId !== null) {
    apiClient.interceptors.response.eject(errorInterceptorId)
    errorInterceptorId = null
  }
}

export function setupErrorHandler() {
  if (errorInterceptorId !== null) {
    apiClient.interceptors.response.eject(errorInterceptorId)
  }

  errorInterceptorId = apiClient.interceptors.response.use(
    (response) => response,
    (error: AxiosError) => {
      // Already considered by this handler or by the 401 interceptor — a
      // retried request re-enters the chain and would otherwise toast twice.
      if (isErrorToastHandled(error) || error.config?.suppressErrorToast) {
        markErrorToastHandled(error)
        return Promise.reject(error)
      }

      if (!error.response) {
        // Auth0-shaped errors have no HTTP response; they are not a dropped connection.
        if (!isAuth0Error(error)) {
          toast.error('Network error. Please check your connection and try again.')
        }
      } else {
        switch (error.response.status) {
          case 401:
            // Leftover 401: refresh not initialized, or already retried.
            // A refresh that *was* attempted has already marked the error
            // (named toast or expired-token silence) and never reaches here.
            toast.error('Your session is no longer valid. Please sign in again.')
            break

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
      }

      markErrorToastHandled(error)
      return Promise.reject(error)
    }
  )
}
