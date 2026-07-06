import { useAuth0 } from '@auth0/auth0-react'
import { LogIn, Loader2 } from 'lucide-react'

/**
 * Login button component for Auth0 authentication
 *
 * Triggers Auth0 Universal Login flow with redirect to /dashboard after successful authentication
 *
 * Features:
 * - Redirects to Auth0 Universal Login page
 * - Returns to /dashboard after successful login
 * - Shows loading state during authentication
 * - Disabled during loading to prevent duplicate requests
 *
 * @author Data Forge Team
 * @version 2.0.0 (Auth0 migration)
 */
export function LoginButton() {
  const { loginWithRedirect, isLoading } = useAuth0()

  const handleLogin = () => {
    loginWithRedirect({
      appState: {
        returnTo: '/dashboard',
      },
    })
  }

  return (
    <button
      onClick={handleLogin}
      disabled={isLoading}
      className="group relative w-full overflow-hidden rounded-lg bg-brand px-6 py-3 text-base font-medium text-white shadow-lg transition-all duration-200 hover:scale-[1.02] hover:shadow-xl disabled:opacity-50 disabled:hover:scale-100"
    >
      <div className="absolute inset-0 bg-brand-hover opacity-0 transition-opacity duration-200 group-hover:opacity-100" />
      <div className="relative flex items-center justify-center gap-2">
        {isLoading ? (
          <>
            <Loader2 className="h-5 w-5 animate-spin" />
            <span>Loading...</span>
          </>
        ) : (
          <>
            <LogIn className="h-5 w-5" />
            <span>Sign In with Auth0</span>
          </>
        )}
      </div>
    </button>
  )
}
