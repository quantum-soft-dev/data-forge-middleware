import { useEffect } from 'react'
import { useAuth0 } from '@auth0/auth0-react'
import { Auth0Provider, QueryProvider, RouterProvider } from '@/app/providers'
import { ErrorBoundary } from '@/app/ErrorBoundary'
import { setupInterceptors, setupResponseInterceptor } from '@/shared/api/interceptors'
import { initTokenRefresh } from '@/shared/api/token-refresh'
import { setupErrorHandler } from '@/shared/api/error-handler'
import { retryReturnTo } from '@/shared/lib/auth/returnTo'
import { SessionExpiredBanner } from '@/entities/user-session/ui/SessionExpiredBanner'
import { Toaster } from 'sonner'

/**
 * Root application component (Auth0 migration)
 *
 * Provider chain (outside → inside):
 * 1. Auth0Provider: Auth0 authentication
 * 2. QueryProvider: TanStack Query for server state
 * 3. RouterProvider: TanStack Router for navigation
 *
 * Interceptors are set up after auth is ready to access tokens.
 *
 * @author Data Forge Team
 * @version 2.0.0 (Auth0 migration)
 */
export function AppContent() {
  const { isLoading, isAuthenticated, error, getAccessTokenSilently, loginWithRedirect, logout } = useAuth0()

  // Setup axios interceptors when auth is ready
  // The interceptor will dynamically get the token on each request via closure
  useEffect(() => {
    console.log('[App] 🔐 Auth state:', {
      isLoading,
      isAuthenticated,
    })

    // Setup request interceptors with Auth0 token getter
    // Token is fetched silently on each request
    setupInterceptors(async () => {
      if (isAuthenticated) {
        try {
          const token = await getAccessTokenSilently()
          return token
        } catch (error) {
          console.error('[App] Failed to get access token:', error)
          return undefined
        }
      }
      return undefined
    })

    // Initialize token refresh manager for automatic 401 handling
    // This enables automatic token refresh when API returns 401
    initTokenRefresh(
      getAccessTokenSilently,
      () => logout({ logoutParams: { returnTo: window.location.origin } })
    )

    // Setup response interceptor for 401 handling with token refresh
    setupResponseInterceptor()

    setupErrorHandler()
  }, [isLoading, isAuthenticated, getAccessTokenSilently, logout])

  // Show loading state while auth is initializing
  if (isLoading) {
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
  if (error) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-destructive">
            Authentication Error
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            {error.message}
          </p>
          <button
            // The current URL rather than the callback's `/` — but never the
            // callback URL itself, whose consumed code/state the SDK would read
            // as a fresh callback on the way back (#211).
            onClick={() => loginWithRedirect({ appState: { returnTo: retryReturnTo() } })}
            className="mt-4 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          >
            Try Again
          </button>
        </div>
      </div>
    )
  }

  return (
    <>
      {/* Show session expiry notification after user logs back in */}
      <SessionExpiredBanner />
      <RouterProvider />
    </>
  )
}

/**
 * App component with providers (Auth0 migration)
 */
function App() {
  return (
    <ErrorBoundary>
      <Auth0Provider>
        <QueryProvider>
          <AppContent />
          {/* Toast notifications */}
          <Toaster
            position="bottom-right"
            closeButton={true}
            richColors={true}
          />
        </QueryProvider>
      </Auth0Provider>
    </ErrorBoundary>
  )
}

export default App
