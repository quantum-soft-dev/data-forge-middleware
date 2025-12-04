import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  setSessionExpired,
  getSessionExpired,
  clearSessionExpired,
  isSessionStorageAvailable,
} from './session-expiry'

describe('session-expiry', () => {
  beforeEach(() => {
    // Clear sessionStorage before each test
    sessionStorage.clear()
  })

  afterEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  describe('isSessionStorageAvailable', () => {
    it('should return true when sessionStorage is available', () => {
      expect(isSessionStorageAvailable()).toBe(true)
    })

    it('should return false when sessionStorage throws', () => {
      // Mock sessionStorage.setItem to throw
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('QuotaExceededError')
      })

      expect(isSessionStorageAvailable()).toBe(false)
    })
  })

  describe('setSessionExpired', () => {
    // T018: Test setSessionExpired() writes to sessionStorage
    it('should write session expiry data to sessionStorage', () => {
      setSessionExpired('refresh_token_expired')

      const stored = sessionStorage.getItem('dfm_session_expired')
      expect(stored).not.toBeNull()

      const data = JSON.parse(stored!)
      expect(data.isExpired).toBe(true)
      expect(data.reason).toBe('refresh_token_expired')
      expect(data.timestamp).toBeDefined()
    })

    it('should store correct reason for manual_logout', () => {
      setSessionExpired('manual_logout')

      const stored = sessionStorage.getItem('dfm_session_expired')
      const data = JSON.parse(stored!)
      expect(data.reason).toBe('manual_logout')
    })

    it('should include ISO timestamp', () => {
      const before = new Date().toISOString()
      setSessionExpired('refresh_token_expired')
      const after = new Date().toISOString()

      const stored = sessionStorage.getItem('dfm_session_expired')
      const data = JSON.parse(stored!)

      // Timestamp should be between before and after
      expect(data.timestamp >= before).toBe(true)
      expect(data.timestamp <= after).toBe(true)
    })

    it('should not throw when sessionStorage is unavailable', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('QuotaExceededError')
      })

      // Should not throw
      expect(() => setSessionExpired('refresh_token_expired')).not.toThrow()
    })
  })

  describe('getSessionExpired', () => {
    // T023: Test getSessionExpired() reads from sessionStorage
    it('should return session expiry data when set', () => {
      setSessionExpired('refresh_token_expired')

      const result = getSessionExpired()

      expect(result).not.toBeNull()
      expect(result?.isExpired).toBe(true)
      expect(result?.reason).toBe('refresh_token_expired')
    })

    it('should return null when no data stored', () => {
      const result = getSessionExpired()
      expect(result).toBeNull()
    })

    it('should return null when stored data is invalid JSON', () => {
      sessionStorage.setItem('dfm_session_expired', 'invalid-json')
      const result = getSessionExpired()
      expect(result).toBeNull()
    })

    // T025: Test graceful handling when sessionStorage unavailable
    it('should return null when sessionStorage throws on read', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('SecurityError')
      })

      const result = getSessionExpired()
      expect(result).toBeNull()
    })
  })

  describe('clearSessionExpired', () => {
    // T024: Test clearSessionExpired() removes from sessionStorage
    it('should remove session expiry data from sessionStorage', () => {
      setSessionExpired('refresh_token_expired')
      expect(sessionStorage.getItem('dfm_session_expired')).not.toBeNull()

      clearSessionExpired()

      expect(sessionStorage.getItem('dfm_session_expired')).toBeNull()
    })

    it('should not throw when nothing to clear', () => {
      expect(() => clearSessionExpired()).not.toThrow()
    })

    it('should not throw when sessionStorage unavailable', () => {
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new Error('SecurityError')
      })

      expect(() => clearSessionExpired()).not.toThrow()
    })
  })
})
