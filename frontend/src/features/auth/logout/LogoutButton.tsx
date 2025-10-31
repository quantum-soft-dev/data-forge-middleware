import { useAuth } from '@/entities/user-session/api/useAuth'
import { LogOut } from 'lucide-react'

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
      className="rounded-md border border-input p-2 text-sm font-medium hover:bg-accent hover:text-accent-foreground transition-colors"
      title="Sign Out"
      aria-label="Sign Out"
    >
      <LogOut className="h-4 w-4" />
    </button>
  )
}
