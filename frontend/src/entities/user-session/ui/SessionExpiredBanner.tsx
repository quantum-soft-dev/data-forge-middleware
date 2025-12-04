import { useEffect, useState } from 'react'
import { Alert, AlertDescription, AlertTitle } from '@/shared/ui/ui/alert'
import { AlertCircle } from 'lucide-react'
import { getSessionExpired, clearSessionExpired } from '@/shared/lib/auth/session-expiry'

/**
 * Banner component that displays session expiry notification.
 *
 * Shows a message when the user's session expired due to inactivity
 * (refresh token expiry). Clears the state after displaying.
 *
 * Does NOT show for manual logouts - only for inactivity-based expiry.
 *
 * @see T032, T033 - Session expiry notification implementation
 * @see specs/012-key-caching-logic/spec.md - US3 User Story
 */
export function SessionExpiredBanner() {
  const [shouldShow, setShouldShow] = useState(false)

  useEffect(() => {
    const expiryData = getSessionExpired()

    // Only show banner for inactivity-based expiry (refresh_token_expired)
    // Don't show for manual logout
    if (expiryData?.isExpired && expiryData.reason === 'refresh_token_expired') {
      setShouldShow(true)
      // Clear the state after reading (one-time display)
      clearSessionExpired()
    }
  }, [])

  if (!shouldShow) {
    return null
  }

  return (
    <Alert
      variant="destructive"
      role="alert"
      aria-live="polite"
      className="mb-4"
    >
      <AlertCircle className="h-4 w-4" />
      <AlertTitle>Session Expired</AlertTitle>
      <AlertDescription>
        Your session has expired due to inactivity. Please log in again.
      </AlertDescription>
    </Alert>
  )
}
