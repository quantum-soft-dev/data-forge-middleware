import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { AuthProvider, QueryProvider, RouterProvider } from '@/app/providers'
import { setupInterceptors } from '@/shared/api/interceptors'
import { setupErrorHandler } from '@/shared/api/error-handler'
import { Toaster } from 'sonner'

/**
 * Root application component
 *
 * Provider chain (outside → inside):
 * 1. AuthProvider: Keycloak OIDC authentication
 * 2. QueryProvider: TanStack Query for server state
 * 3. RouterProvider: TanStack Router for navigation
 *
 * Interceptors are set up after auth is ready to access tokens.
 */
function AppContent() {
  const auth = useAuth()

  // Setup axios interceptors when auth is ready
  // The interceptor will dynamically get the token on each request via closure
  useEffect(() => {
    console.log('[App] 🔐 Auth state:', {
      isLoading: auth.isLoading,
      isAuthenticated: auth.isAuthenticated,
      hasUser: !!auth.user,
      hasToken: !!auth.user?.access_token,
    })

    // Always setup interceptors (even if not authenticated yet)
    // The getter function will return undefined if no token, and the interceptor handles that
    setupInterceptors(() => auth.user?.access_token)
    setupErrorHandler()
  }, [auth.isLoading, auth.isAuthenticated, auth.user])

  // Show loading state while auth is initializing
  if (auth.isLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-center">
          <div className="mb-4 inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-current border-r-transparent motion-reduce:animate-[spin_1.5s_linear_infinite]" />
          <p className="text-sm text-muted-foreground">Loading...</p>
        </div>
      </div>
    )
  }

  // Show error state if auth failed
  if (auth.error) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-destructive">
            Authentication Error
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            {auth.error.message}
          </p>
          <button
            onClick={() => auth.signinRedirect()}
            className="mt-4 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          >
            Try Again
          </button>
        </div>
      </div>
    )
  }

  return <RouterProvider />
}

/**
 * App component with providers
 */
function App() {
  return (
    <AuthProvider>
      <QueryProvider>
        <AppContent />
        {/* Toast notifications */}
        <Toaster position="top-right" />
      </QueryProvider>
    </AuthProvider>
  )
}

export default App
