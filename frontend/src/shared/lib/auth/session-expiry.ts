import type { SessionExpiryData, SessionExpiryReason } from '@/shared/api/types'

/**
 * Session expiry state management.
 *
 * Uses sessionStorage to persist session expiry state across redirects.
 * State is automatically cleared when tab is closed.
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see specs/012-key-caching-logic/research.md
 */

/** Storage key for session expiry data */
const STORAGE_KEY = 'dfm_session_expired'

/**
 * Set session expired flag in sessionStorage.
 *
 * Called before logout when refresh token expires.
 *
 * @param reason - Why the session expired
 */
export function setSessionExpired(reason: SessionExpiryReason): void {
  if (!isSessionStorageAvailable()) {
    return
  }

  const data: SessionExpiryData = {
    isExpired: true,
    reason,
    timestamp: new Date().toISOString(),
  }

  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch {
    // Ignore storage errors (quota exceeded, etc.)
  }
}

/**
 * Get session expiry data from sessionStorage.
 *
 * Returns null if no expiry data or sessionStorage unavailable.
 *
 * @returns Session expiry data or null
 */
export function getSessionExpired(): SessionExpiryData | null {
  if (!isSessionStorageAvailable()) {
    return null
  }

  try {
    const stored = sessionStorage.getItem(STORAGE_KEY)
    if (!stored) {
      return null
    }
    return JSON.parse(stored) as SessionExpiryData
  } catch {
    return null
  }
}

/**
 * Clear session expiry flag from sessionStorage.
 *
 * Called after displaying expiry message to user.
 */
export function clearSessionExpired(): void {
  if (!isSessionStorageAvailable()) {
    return
  }

  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Ignore storage errors
  }
}

/**
 * Check if session storage is available.
 * Some browsers disable it in private mode.
 */
export function isSessionStorageAvailable(): boolean {
  try {
    const testKey = '__test__'
    sessionStorage.setItem(testKey, testKey)
    sessionStorage.removeItem(testKey)
    return true
  } catch {
    return false
  }
}
