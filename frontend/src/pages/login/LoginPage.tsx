import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAuth } from '@/entities/user-session/api/useAuth'
import { LoginButton } from '@/features/auth/login/LoginButton'
import { Database, Shield } from 'lucide-react'

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
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 via-indigo-50 to-purple-50 p-4">
      <div className="w-full max-w-md">
        {/* Logo/Icon */}
        <div className="mb-8 flex justify-center">
          <div className="relative">
            <div className="absolute inset-0 animate-pulse rounded-full bg-gradient-to-r from-blue-400 to-purple-400 opacity-30 blur-xl" />
            <div className="relative flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-purple-600 shadow-lg">
              <Database className="h-10 w-10 text-white" strokeWidth={2} />
            </div>
          </div>
        </div>

        {/* Card */}
        <div className="space-y-6 rounded-2xl border border-white/20 bg-white/80 p-8 shadow-2xl backdrop-blur-sm">
          {/* Header */}
          <div className="text-center">
            <h1 className="bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-3xl font-extrabold tracking-tight text-transparent">
              DataForge Middleware
            </h1>
            <p className="mt-3 text-sm text-gray-600">
              Sign in to access account management
            </p>
          </div>

          {/* Error display */}
          {auth.error && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4">
              <p className="text-sm font-medium text-red-800">
                {auth.error.message || 'Authentication failed. Please try again.'}
              </p>
            </div>
          )}

          {/* Login button */}
          <div className="space-y-4">
            <LoginButton />
          </div>

          {/* Footer */}
          <div className="flex items-center justify-center gap-2 border-t border-gray-200 pt-6">
            <Shield className="h-4 w-4 text-gray-400" />
            <p className="text-xs text-gray-500">
              Protected by Keycloak SSO
            </p>
          </div>
        </div>

        {/* Additional info */}
        <p className="mt-6 text-center text-xs text-gray-500">
          Secure enterprise-grade authentication
        </p>
      </div>
    </div>
  )
}
