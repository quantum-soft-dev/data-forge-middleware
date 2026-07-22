import { useAuth0 } from '@auth0/auth0-react'
import { LogOut } from 'lucide-react'

/**
 * Logout button component for Auth0 authentication
 *
 * Terminates user session and clears Auth0 tokens. Redirects user back to application origin after logout.
 *
 * Features:
 * - Triggers Auth0 logout flow
 * - Clears Auth0 session and tokens
 * - Redirects to application origin (home page)
 * - Hidden when user is not authenticated
 * - Accessible with aria-label
 *
 * @author Data Forge Team
 * @version 2.0.0 (Auth0 migration)
 */
export function LogoutButton() {
  const { logout, isAuthenticated } = useAuth0()

  const handleLogout = () => {
    logout({
      logoutParams: {
        returnTo: window.location.origin,
      },
    })
  }

  if (!isAuthenticated) {
    return null
  }

  return (
    <button
      onClick={handleLogout}
      className="rounded-lg p-2 text-sm font-medium text-ink-secondary hover:bg-secondary hover:text-ink transition-colors"
      title="Sign Out"
      aria-label="Sign Out"
    >
      <LogOut className="h-4 w-4" strokeWidth={1.5} />
    </button>
  )
}
