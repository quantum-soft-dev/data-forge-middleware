import { useAuth } from '@/entities/user-session/api/useAuth'
import { LogIn, Loader2 } from 'lucide-react'

/**
 * Login button component
 *
 * Triggers Keycloak OAuth 2.0 login flow via react-oidc-context
 */
export function LoginButton() {
  const auth = useAuth()

  const handleLogin = () => {
    auth.signinRedirect()
  }

  return (
    <button
      onClick={handleLogin}
      disabled={auth.isLoading}
      className="group relative w-full overflow-hidden rounded-lg bg-gradient-to-r from-blue-600 to-purple-600 px-6 py-3 text-base font-semibold text-white shadow-lg transition-all duration-200 hover:scale-[1.02] hover:shadow-xl disabled:opacity-50 disabled:hover:scale-100"
    >
      <div className="absolute inset-0 bg-gradient-to-r from-blue-700 to-purple-700 opacity-0 transition-opacity duration-200 group-hover:opacity-100" />
      <div className="relative flex items-center justify-center gap-2">
        {auth.isLoading ? (
          <>
            <Loader2 className="h-5 w-5 animate-spin" />
            <span>Loading...</span>
          </>
        ) : (
          <>
            <LogIn className="h-5 w-5" />
            <span>Sign In with Keycloak</span>
          </>
        )}
      </div>
    </button>
  )
}
