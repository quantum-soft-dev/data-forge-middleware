import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAuth } from '@/entities/user-session/api/useAuth'
import { LoginButton } from '@/features/auth/login/LoginButton'

/**
 * Login page
 *
 * Shows login button for unauthenticated users.
 * Redirects to dashboard if already authenticated.
 * Displays Keycloak branding and error messages.
 */
export default function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()

  // Redirect to dashboard if already authenticated
  useEffect(() => {
    if (auth.isAuthenticated) {
      navigate({ to: '/dashboard' })
    }
  }, [auth.isAuthenticated, navigate])

  // Show loading state while checking auth
  if (auth.isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100">
        <div className="text-center">
          <div className="mb-4 inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-primary border-r-transparent" />
          <p className="text-sm text-muted-foreground">Loading...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-slate-100">
      <div className="w-full max-w-md space-y-8 rounded-lg bg-white p-8 shadow-lg">
        {/* Header */}
        <div className="text-center">
          <h1 className="text-3xl font-bold tracking-tight text-gray-900">
            DataForge Middleware
          </h1>
          <p className="mt-2 text-sm text-gray-600">
            Sign in to access subscriber management
          </p>
        </div>

        {/* Error display */}
        {auth.error && (
          <div className="rounded-md bg-destructive/10 p-4">
            <p className="text-sm text-destructive">
              {auth.error.message || 'Authentication failed. Please try again.'}
            </p>
          </div>
        )}

        {/* Login button */}
        <div className="mt-6">
          <LoginButton />
        </div>

        {/* Footer */}
        <p className="mt-4 text-center text-xs text-gray-500">
          Protected by Keycloak SSO
        </p>
      </div>
    </div>
  )
}
