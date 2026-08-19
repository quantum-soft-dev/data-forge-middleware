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
 * Note: 401 is *meant* to be left to the response interceptor in interceptors.ts,
 * which attempts a token refresh first. This handler does not currently stay out
 * of its way, by four routes — all of them known open defects rather than the
 * design, and all recorded as a side finding of issue #225:
 *
 *   1. The switch below has no `case 401`, so a 401 that interceptor rejects
 *      without refreshing (already retried, or refresh not initialized) reaches
 *      `default:`.
 *   2. A *failed* refresh rejects with the Auth0 error rather than the original
 *      response. It carries no `.response`, so it reads here as a network error —
 *      including on the expired-refresh-token branch, where interceptors.ts stays
 *      deliberately silent so the logout redirect is quiet.
 *   3. That same Auth0 error carries no `.config` either, so `suppressErrorToast`
 *      is lost and a caller that opted out of the global toast gets one anyway.
 *   4. A refresh that *succeeds* retries via `apiClient.request(...)`, which
 *      re-enters this whole chain: if the retry fails too, the inner request
 *      toasts and the outer rejection toasts again — two toasts for one failure,
 *      which no amount of registration hygiene changes.
 *
 * Not fixed with #225, whose subject is how many times this interceptor is
 * registered: what a failed refresh should say is a behaviour decision of its own.
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

export function setupErrorHandler() {
  if (errorInterceptorId !== null) {
    apiClient.interceptors.response.eject(errorInterceptorId)
  }

  errorInterceptorId = apiClient.interceptors.response.use(
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
        // 401 has no case of its own and therefore reaches `default:`.
        // setupResponseInterceptor() in interceptors.ts refreshes the token
        // first but does not stop the rejection reaching this handler — see
        // the file docblock, a known open defect rather than the intent.

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
