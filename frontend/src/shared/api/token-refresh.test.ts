import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  initTokenRefresh,
  refreshTokenWithLock,
  isTokenRefreshInitialized,
  isRefreshInProgress,
  resetTokenRefreshState,
} from './token-refresh'

describe('token-refresh', () => {
  // Mock Auth0 callbacks
  const mockGetAccessToken = vi.fn()
  const mockLogout = vi.fn()

  beforeEach(() => {
    // Reset state before each test
    resetTokenRefreshState()
    vi.clearAllMocks()
  })

  afterEach(() => {
    resetTokenRefreshState()
  })

  describe('initTokenRefresh', () => {
    it('should initialize with Auth0 callbacks', () => {
      expect(isTokenRefreshInitialized()).toBe(false)

      initTokenRefresh(mockGetAccessToken, mockLogout)

      expect(isTokenRefreshInitialized()).toBe(true)
    })
  })

  describe('refreshTokenWithLock', () => {
    beforeEach(() => {
      initTokenRefresh(mockGetAccessToken, mockLogout)
    })

    // T005: Test refreshTokenWithLock returns new token on success
    it('should return new token on successful refresh', async () => {
      const newToken = 'new-access-token-123'
      mockGetAccessToken.mockResolvedValueOnce(newToken)

      const result = await refreshTokenWithLock()

      expect(result).toBe(newToken)
      expect(mockGetAccessToken).toHaveBeenCalledTimes(1)
      expect(mockGetAccessToken).toHaveBeenCalledWith({ cacheMode: 'off' })
    })

    // T006: Test lock prevents multiple concurrent refreshes
    it('should prevent multiple concurrent refresh attempts', async () => {
      const newToken = 'new-access-token-123'

      // Create a delayed promise to simulate network latency
      let resolveRefresh: (value: string) => void
      const delayedPromise = new Promise<string>((resolve) => {
        resolveRefresh = resolve
      })
      mockGetAccessToken.mockReturnValueOnce(delayedPromise)

      // Start multiple concurrent refresh calls
      const promise1 = refreshTokenWithLock()
      const promise2 = refreshTokenWithLock()
      const promise3 = refreshTokenWithLock()

      // Verify refresh is in progress
      expect(isRefreshInProgress()).toBe(true)

      // Resolve the refresh
      resolveRefresh!(newToken)

      // All promises should resolve to the same token
      const [result1, result2, result3] = await Promise.all([
        promise1,
        promise2,
        promise3,
      ])

      expect(result1).toBe(newToken)
      expect(result2).toBe(newToken)
      expect(result3).toBe(newToken)

      // getAccessToken should only be called ONCE
      expect(mockGetAccessToken).toHaveBeenCalledTimes(1)
    })

    // T006 continued: Test lock resets after refresh completes
    it('should reset lock after refresh completes allowing new refresh', async () => {
      mockGetAccessToken
        .mockResolvedValueOnce('token-1')
        .mockResolvedValueOnce('token-2')

      // First refresh
      const result1 = await refreshTokenWithLock()
      expect(result1).toBe('token-1')
      expect(isRefreshInProgress()).toBe(false)

      // Second refresh (should work, not blocked)
      const result2 = await refreshTokenWithLock()
      expect(result2).toBe('token-2')

      expect(mockGetAccessToken).toHaveBeenCalledTimes(2)
    })

    // T015: Test invalid_grant error triggers logout (User Story 2)
    it('should call logout when refresh fails with invalid_grant', async () => {
      const auth0Error = new Error('invalid_grant') as Error & { error: string }
      auth0Error.error = 'invalid_grant'
      mockGetAccessToken.mockRejectedValueOnce(auth0Error)

      await expect(refreshTokenWithLock()).rejects.toThrow()

      expect(mockLogout).toHaveBeenCalledTimes(1)
    })

    // T016: Test missing_refresh_token error triggers logout (User Story 2)
    it('should call logout when refresh fails with missing_refresh_token', async () => {
      const auth0Error = new Error('missing_refresh_token') as Error & {
        error: string
      }
      auth0Error.error = 'missing_refresh_token'
      mockGetAccessToken.mockRejectedValueOnce(auth0Error)

      await expect(refreshTokenWithLock()).rejects.toThrow()

      expect(mockLogout).toHaveBeenCalledTimes(1)
    })

    // T017: Test login_required error triggers logout (User Story 2)
    it('should call logout when refresh fails with login_required', async () => {
      const auth0Error = new Error('login_required') as Error & { error: string }
      auth0Error.error = 'login_required'
      mockGetAccessToken.mockRejectedValueOnce(auth0Error)

      await expect(refreshTokenWithLock()).rejects.toThrow()

      expect(mockLogout).toHaveBeenCalledTimes(1)
    })

    // Test: Network error should NOT trigger logout
    it('should NOT call logout on network error', async () => {
      const networkError = new Error('Network Error')
      mockGetAccessToken.mockRejectedValueOnce(networkError)

      await expect(refreshTokenWithLock()).rejects.toThrow('Network Error')

      expect(mockLogout).not.toHaveBeenCalled()
    })

    // Test: Lock should reset even on error
    it('should reset lock state after error', async () => {
      mockGetAccessToken.mockRejectedValueOnce(new Error('Some error'))

      await expect(refreshTokenWithLock()).rejects.toThrow()

      expect(isRefreshInProgress()).toBe(false)

      // Next refresh should work
      mockGetAccessToken.mockResolvedValueOnce('new-token')
      const result = await refreshTokenWithLock()
      expect(result).toBe('new-token')
    })
  })

  describe('isTokenRefreshInitialized', () => {
    it('should return false when not initialized', () => {
      expect(isTokenRefreshInitialized()).toBe(false)
    })

    it('should return true after initialization', () => {
      initTokenRefresh(mockGetAccessToken, mockLogout)
      expect(isTokenRefreshInitialized()).toBe(true)
    })
  })
})
