import { useAuth } from '@/entities/user-session/api/useAuth'

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
      className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
    >
      {auth.isLoading ? 'Loading...' : 'Sign In with Keycloak'}
    </button>
  )
}
