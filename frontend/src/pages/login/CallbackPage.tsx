import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAuth } from '@/entities/user-session/api/useAuth'

/**
 * OAuth callback page
 *
 * Handles the redirect from Keycloak after authentication.
 * react-oidc-context automatically processes the authorization code.
 * This page just shows a loading state and redirects to dashboard on success.
 */
export default function CallbackPage() {
  const auth = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (auth.isAuthenticated) {
      // Redirect to dashboard after successful authentication
      navigate({ to: '/dashboard' })
    } else if (auth.error) {
      // Redirect back to login on error
      navigate({ to: '/' })
    }
  }, [auth.isAuthenticated, auth.error, navigate])

  return (
    <div className="flex h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100">
      <div className="text-center">
        <div className="mb-4 inline-block h-12 w-12 animate-spin rounded-full border-4 border-solid border-primary border-r-transparent" />
        <h1 className="text-2xl font-semibold text-gray-900">
          Completing Sign In...
        </h1>
        <p className="mt-2 text-sm text-gray-600">
          Please wait while we authenticate you
        </p>
      </div>
    </div>
  )
}
