import { useAuth } from '@/entities/user-session/api/useAuth'

/**
 * Logout button component
 *
 * Terminates user session and clears Keycloak tokens
 */
export function LogoutButton() {
  const auth = useAuth()

  const handleLogout = () => {
    auth.signoutRedirect()
  }

  if (!auth.isAuthenticated) {
    return null
  }

  return (
    <button
      onClick={handleLogout}
      className="rounded-md border border-input px-4 py-2 text-sm font-medium hover:bg-accent hover:text-accent-foreground"
    >
      Sign Out
    </button>
  )
}
