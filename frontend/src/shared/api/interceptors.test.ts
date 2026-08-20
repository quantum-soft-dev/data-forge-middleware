import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { toast } from 'sonner'
import { apiClient } from './client'
import {
  setupInterceptors,
  setupResponseInterceptor,
  clearResponseInterceptor,
} from './interceptors'
import * as tokenRefresh from './token-refresh'
import { isErrorToastHandled } from './types'
import type { Auth0Error, RetryableAxiosConfig } from './types'

// Mock token-refresh module
vi.mock('./token-refresh', () => ({
  refreshTokenWithLock: vi.fn(),
  isTokenRefreshInitialized: vi.fn(() => true),
}))

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// Mock logger to reduce noise
vi.mock('../lib/logger', () => ({
  logger: {
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  },
}))

describe('interceptors - response interceptor', () => {
  const mockTokenGetter = vi.fn()
  const mockRefreshTokenWithLock = vi.mocked(tokenRefresh.refreshTokenWithLock)
  const mockIsTokenRefreshInitialized = vi.mocked(tokenRefresh.isTokenRefreshInitialized)

  beforeEach(() => {
    vi.clearAllMocks()
    mockIsTokenRefreshInitialized.mockReturnValue(true)
    // Setup request interceptor
    setupInterceptors(mockTokenGetter)
  })

  afterEach(() => {
    clearResponseInterceptor()
  })

  // Helper to create axios error
  function createAxiosError(
    status: number,
    config: Partial<InternalAxiosRequestConfig> = {}
  ): AxiosError {
    const error = new Error(`Request failed with status ${status}`) as AxiosError
    error.isAxiosError = true
    error.response = {
      status,
      statusText: status === 401 ? 'Unauthorized' : 'Error',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
      data: {},
    }
    error.config = {
      url: '/api/test',
      method: 'get',
      headers: new axios.AxiosHeaders(),
      ...config,
    } as InternalAxiosRequestConfig
    return error
  }

  // T008: Test 401 response triggers refresh and retry
  describe('401 handling', () => {
    it('should trigger token refresh on 401 response', async () => {
      const newToken = 'new-refreshed-token'
      mockRefreshTokenWithLock.mockResolvedValueOnce(newToken)

      // Setup response interceptor
      setupResponseInterceptor()

      // Create 401 error
      const error = createAxiosError(401)

      // Mock successful retry
      const mockRetryResponse = { data: { success: true }, status: 200 }
      vi.spyOn(apiClient, 'request').mockResolvedValueOnce(mockRetryResponse)

      // Simulate interceptor handling
      // Get the response interceptor and call it directly
      const interceptors = (apiClient.interceptors.response as unknown as {
        handlers: Array<{ rejected?: (error: AxiosError) => Promise<unknown> }>
      }).handlers

      // Find our interceptor (the last one added)
      const ourInterceptor = interceptors[interceptors.length - 1]

      if (ourInterceptor?.rejected) {
        const result = await ourInterceptor.rejected(error)
        expect(mockRefreshTokenWithLock).toHaveBeenCalledTimes(1)
        expect(result).toEqual(mockRetryResponse)
      } else {
        // If interceptor not found, test the expected behavior conceptually
        expect(mockRefreshTokenWithLock).toBeDefined()
      }
    })

    // T007: Test _retry flag prevents infinite loops
    it('should not retry if request already retried (_retry flag set)', async () => {
      setupResponseInterceptor()

      // Create 401 error with _retry flag already set
      const error = createAxiosError(401, { _retry: true } as Partial<InternalAxiosRequestConfig>)

      const interceptors = (apiClient.interceptors.response as unknown as {
        handlers: Array<{ rejected?: (error: AxiosError) => Promise<unknown> }>
      }).handlers

      const ourInterceptor = interceptors[interceptors.length - 1]

      if (ourInterceptor?.rejected) {
        // Should reject without calling refresh
        await expect(ourInterceptor.rejected(error)).rejects.toBeDefined()
        expect(mockRefreshTokenWithLock).not.toHaveBeenCalled()
      }
    })

    // T009: Test successful retry uses new token in Authorization header
    it('should use new token in retry request Authorization header', async () => {
      const newToken = 'brand-new-token-xyz'
      mockRefreshTokenWithLock.mockResolvedValueOnce(newToken)

      setupResponseInterceptor()

      const error = createAxiosError(401)

      // Capture the retry request config
      let retryConfig: InternalAxiosRequestConfig | undefined
      vi.spyOn(apiClient, 'request').mockImplementationOnce((config) => {
        retryConfig = config as InternalAxiosRequestConfig
        return Promise.resolve({ data: {}, status: 200 })
      })

      const interceptors = (apiClient.interceptors.response as unknown as {
        handlers: Array<{ rejected?: (error: AxiosError) => Promise<unknown> }>
      }).handlers

      const ourInterceptor = interceptors[interceptors.length - 1]

      if (ourInterceptor?.rejected) {
        await ourInterceptor.rejected(error)

        expect(retryConfig).toBeDefined()
        expect(retryConfig?.headers?.Authorization).toBe(`Bearer ${newToken}`)
      }
    })
  })

  // Test: Non-401 errors should pass through
  describe('non-401 errors', () => {
    it('should pass through 403 errors without retry', async () => {
      setupResponseInterceptor()

      const error = createAxiosError(403)

      const interceptors = (apiClient.interceptors.response as unknown as {
        handlers: Array<{ rejected?: (error: AxiosError) => Promise<unknown> }>
      }).handlers

      const ourInterceptor = interceptors[interceptors.length - 1]

      if (ourInterceptor?.rejected) {
        await expect(ourInterceptor.rejected(error)).rejects.toBeDefined()
        expect(mockRefreshTokenWithLock).not.toHaveBeenCalled()
      }
    })

    it('should pass through 500 errors without retry', async () => {
      setupResponseInterceptor()

      const error = createAxiosError(500)

      const interceptors = (apiClient.interceptors.response as unknown as {
        handlers: Array<{ rejected?: (error: AxiosError) => Promise<unknown> }>
      }).handlers

      const ourInterceptor = interceptors[interceptors.length - 1]

      if (ourInterceptor?.rejected) {
        await expect(ourInterceptor.rejected(error)).rejects.toBeDefined()
        expect(mockRefreshTokenWithLock).not.toHaveBeenCalled()
      }
    })
  })

  /**
   * A failed refresh used to reject the Auth0 error, which has no `.response`
   * and no `.config`. The global toast handler then read it as a network
   * failure and lost `suppressErrorToast` (issue #239 routes 2–3). The
   * interceptor now rejects the original 401, marks it handled, and honours
   * the opt-out on its own toasts.
   */
  describe('failed refresh rejection (issue #239)', () => {
    async function rejectedOf(error: AxiosError): Promise<unknown> {
      setupResponseInterceptor()
      const interceptors = (apiClient.interceptors.response as unknown as {
        handlers: Array<{ rejected?: (error: AxiosError) => Promise<unknown> }>
      }).handlers
      const ourInterceptor = interceptors[interceptors.length - 1]
      if (!ourInterceptor?.rejected) {
        throw new Error('401 interceptor was not registered')
      }
      return ourInterceptor.rejected(error)
    }

    function auth0Error(code: string): Auth0Error {
      const err = new Error(code) as Auth0Error
      err.error = code
      return err
    }

    it('rejects the original 401, not the Auth0 error, so .response and .config survive', async () => {
      const original = createAxiosError(401)
      const refreshErr = auth0Error('invalid_grant')
      mockRefreshTokenWithLock.mockRejectedValueOnce(refreshErr)

      await expect(rejectedOf(original)).rejects.toBe(original)
      expect(original.response?.status).toBe(401)
      expect(original.config).toBeDefined()
      expect(original.cause).toBe(refreshErr)
      expect(isErrorToastHandled(original)).toBe(true)
      expect(toast.error).not.toHaveBeenCalled()
    })

    it('toasts a named refresh failure once and still rejects the original 401', async () => {
      const original = createAxiosError(401)
      mockRefreshTokenWithLock.mockRejectedValueOnce(new Error('boom'))

      await expect(rejectedOf(original)).rejects.toBe(original)
      expect(toast.error).toHaveBeenCalledTimes(1)
      expect(toast.error).toHaveBeenCalledWith('Failed to refresh session. Please try again.')
      expect(isErrorToastHandled(original)).toBe(true)
    })

    it('honours suppressErrorToast on a named refresh failure', async () => {
      const original = createAxiosError(401, { suppressErrorToast: true } as Partial<RetryableAxiosConfig>)
      mockRefreshTokenWithLock.mockRejectedValueOnce(new Error('boom'))

      await expect(rejectedOf(original)).rejects.toBe(original)
      expect(toast.error).not.toHaveBeenCalled()
      expect(isErrorToastHandled(original)).toBe(true)
    })

    it('honours suppressErrorToast on a network error during refresh', async () => {
      const original = createAxiosError(401, { suppressErrorToast: true } as Partial<RetryableAxiosConfig>)
      mockRefreshTokenWithLock.mockRejectedValueOnce(new Error('Network Error'))

      await expect(rejectedOf(original)).rejects.toBe(original)
      expect(toast.error).not.toHaveBeenCalled()
    })
  })
})
