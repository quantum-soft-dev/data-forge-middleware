import { ComponentType, useEffect } from 'react';
import { useAuth0 } from '@auth0/auth0-react';
import { currentReturnTo } from './returnTo';

/**
 * Loading spinner component shown during authentication redirect.
 */
function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center min-h-screen">
      <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-gray-900"></div>
    </div>
  );
}

/**
 * Guard that protects routes requiring authentication.
 *
 * Usage:
 * ```tsx
 * <Route path="/dashboard" element={<AuthenticationGuard component={DashboardPage} />} />
 * ```
 *
 * Behavior:
 * - If user is authenticated: renders the protected component
 * - If user is not authenticated: redirects to Auth0 Universal Login
 * - During redirect: shows loading spinner
 * - After login: navigates back to original route
 *
 * This inlines what `withAuthenticationRequired` does instead of calling it:
 * the HOC returns a new component type per call, so calling it while rendering
 * remounted the protected subtree (and reset its state) on every render.
 *
 * @param component - The component to protect (must be a React component)
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
interface AuthenticationGuardProps {
  component: ComponentType;
}

export function AuthenticationGuard({ component: Component }: AuthenticationGuardProps) {
  const { isAuthenticated, isLoading, loginWithRedirect } = useAuth0();

  useEffect(() => {
    if (isLoading || isAuthenticated) return;

    void loginWithRedirect({
      appState: { returnTo: currentReturnTo() },
    });
  }, [isAuthenticated, isLoading, loginWithRedirect]);

  if (!isAuthenticated) {
    return <LoadingSpinner />;
  }

  return <Component />;
}
